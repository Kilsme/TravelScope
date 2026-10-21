package com.travelscope.agent;

import com.travelscope.common.TracingHelper;
import com.travelscope.collab.TaskWorkspace;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reviewer 回炉保险丝（R4：拦 reviewer 的修订轮次，上限 1 次）
 * <p>
 * 卡 B 起回炉闭环移入 reviewer 内部（评审→修订→复审，不再经 planner 重送审）。
 * planner 每轮规划只 spawn reviewer 一次，旧的「拦 planner 第 3+ 次 spawn」保险丝不再触发，
 * 新的保险丝落在 reviewer 自己的工具链上：拦「reviewer 第 2+ 次 revise_poi_selection」。
 * </p>
 * <p>
 * R4（v6）：上限 2→1——R1 质检分级后能走到回炉的都是 HARD 问题，软问题已不占回炉额度，
 * 一次修订不收敛即带问题交付（DEGRADED 三件套：ERROR 锚点 review_retry_exceeded /
 * review_report.json 标记 deliveryMode=DEGRADED+未解决清单 / done 载荷 caveat，
 * 契约标记由编排层 ChatService step5 落，本中间件只负责拦截与锚点日志）。
 * </p>
 * <p>
 * 挂载方式：本中间件加在 buildPlannerAgent 的 plannerMiddlewares（挂在 planner 上）；
 * 框架 buildDeclaredFactory 会把父 agent 的 middlewares 列表 copy 给 spawn 出的子 agent
 * （reviewer），故本中间件自动生效于 reviewer 的 onActing——onActing 里用
 * {@code agent.getName()} 过滤，只拦 reviewer-agent 的 revise_poi_selection。
 * 计次键同旧版 userId:sessionId（reviewer 与 planner 共享 RuntimeContext）。
 * </p>
 */
public class ReviewerRetryMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ReviewerRetryMiddleware.class);

    /** 回炉（修订）上限（R4：reviewer 首次评审 + 最多 1 次修订重写草案） */
    public static final int MAX_REVIEW_RETRIES = 1;

    /** 被拦截时替换执行的工具名（必须在 Toolkit 中真实注册，见 TaskTools.reviewRetryHint） */
    public static final String RETRY_HINT_TOOL = "review_retry_hint";

    private final TaskWorkspace taskWorkspaceService;
    /** 业务打点（review-attempt span） */
    private final TracingHelper tracing;

    /** userId:sessionId → 修订计数（跨轮持久于进程内；会话粒度隔离） */
    private final Map<String, AtomicInteger> spawnCounts = new ConcurrentHashMap<>();

    /** 评审产物中的总分行（旧 .md 兜底用；JSON 产物走 readLatestReviewOutcome typed 读取） */
    private static final Pattern SCORE_PATTERN = Pattern.compile("总分[:：]\\s*(\\d+)");

    public ReviewerRetryMiddleware(TaskWorkspace taskWorkspaceService) {
        this(taskWorkspaceService, TracingHelper.NOOP);
    }

    public ReviewerRetryMiddleware(TaskWorkspace taskWorkspaceService, TracingHelper tracing) {
        this.taskWorkspaceService = taskWorkspaceService;
        this.tracing = tracing;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     java.util.function.Function<ActingInput, Flux<AgentEvent>> next) {
        // 只拦 reviewer-agent 的 POI 重选修订动作（其余透传）
        boolean isReviewerRevise = ReviewerAgent.AGENT_NAME.equals(agent.getName())
                && input.toolCalls().stream().anyMatch(this::isReviseCall);
        if (!isReviewerRevise) {
            return next.apply(input);
        }

        String userId = ctx.getUserId();
        String sessionId = ctx.getSessionId();

        // 已收敛通过 → 重置计数放行
        if (reviewPassedFileExists(userId, sessionId)) {
            spawnCounts.remove(containerKey(userId, sessionId));
            log.info("review_passed 已存在，修订计数重置: 用户={}, 会话={}", userId, sessionId);
            return next.apply(input);
        }

        int attempt = spawnCounts.computeIfAbsent(containerKey(userId, sessionId),
                k -> new AtomicInteger(0)).incrementAndGet();
        recordReviewAttempt(userId, sessionId, attempt);

        // 未超限（≤ MAX_REVIEW_RETRIES 次修订）→ 放行
        if (attempt <= MAX_REVIEW_RETRIES) {
            log.info("review_retry 修订 count={}（第 {} 次修订） 用户={}, 会话={}",
                    attempt, attempt, userId, sessionId);
            return next.apply(input);
        }

        // 超限：第 2+ 次修订 → 改写为 hint 工具，拦这次 POI 重选
        // R4 ERROR 锚点（旧版仅 warn 静默粘滞收尾）：交付降级为「当前最佳版本（已尽力）」
        // 必须可 grep、可告警；附带最近一轮评审摘要（result/score）
        Map<String, String> lastOutcome = new HashMap<>();
        readLatestReviewOutcome(userId, sessionId, lastOutcome);
        log.error("review_retry_exceeded: 修订尝试 {} 次超过上限 {}（R4），本次 revise_poi_selection 已拦截，"
                        + "本轮将按当前最佳版本带问题交付（DEGRADED）: 用户={}, 会话={}, 最近评审={}/{}",
                attempt, MAX_REVIEW_RETRIES, userId, sessionId,
                lastOutcome.getOrDefault("result", "?"), lastOutcome.getOrDefault("score", "?"));
        String hint = "RETRY_LIMIT_REACHED: 修订已达上限（" + MAX_REVIEW_RETRIES
                + " 次），本次 POI 修订已被系统拦截未执行。"
                + "请立即停止修订，按当前最佳版本收尾：写 review_report.json（若尚无）"
                + "并输出 REVIEW_RESULT: FAIL 总分=xx 失败段=…，"
                + "摘要标注「⚠️ 当前最佳版本（已尽力，评分 x/100）」并列出未解决项。";
        List<ToolUseBlock> rewritten = new ArrayList<>();
        for (ToolUseBlock call : input.toolCalls()) {
            if (isReviseCall(call)) {
                rewritten.add(new ToolUseBlock(call.getId(), RETRY_HINT_TOOL,
                        Map.of("reason", hint)));
            } else {
                rewritten.add(call);
            }
        }
        return next.apply(new ActingInput(rewritten));
    }

    /** 是否为「reviewer 的 POI 重选修订」调用（卡 E：拦 revise_poi_selection） */
    private boolean isReviseCall(ToolUseBlock call) {
        return "revise_poi_selection".equals(call.getName());
    }

    private static String containerKey(String userId, String sessionId) {
        return userId + ":" + sessionId;
    }

    /**
     * review-attempt 打点：attempt + 最近一次评审产物的 result/score（若有）。
     * <p>
     * REVIEW_RESULT 首行（REVIEW_RESULT: PASS|FAIL 总分=xx，ReviewerAgent 提示词第七节）
     * 在子代理回复文本里，应用代码观测不到；其代码可见等价物是评审产物文件——
     * review_passed.md（PASS）/ review_report.md（FAIL），两者均含「总分: xx/100」行。
     * 首次送审尚无产物 → 仅 attempt 属性；回炉送审 → 带上一轮的 FAIL + score。
     * </p>
     */
    private void recordReviewAttempt(String userId, String sessionId, int attempt) {
        try {
            Map<String, String> attrs = new HashMap<>();
            attrs.put("attempt", String.valueOf(attempt));
            readLatestReviewOutcome(userId, sessionId, attrs);
            // 经会话键显式挂到 chat-turn 下（框架中间件线程无 trace 上下文）
            tracing.recordChildSpan(userId, sessionId, "review-attempt", attrs);
        } catch (Exception e) {
            log.debug("review-attempt 打点失败（不影响主流程）: {}", e.getMessage());
        }
    }

    /** 读取最近一次评审结论写入打点属性：S2.7 起 JSON 契约优先（typed total），旧 .md 正则兜底 */
    private void readLatestReviewOutcome(String userId, String sessionId, Map<String, String> attrs) {
        try {
            var passedJson = taskWorkspaceService.readReviewPassed(userId, sessionId);
            if (passedJson.isPresent()) {
                attrs.put("result", "PASS");
                attrs.put("score", String.valueOf(passedJson.get().total()));
                return;
            }
            var reportJson = taskWorkspaceService.readReviewReport(userId, sessionId);
            if (reportJson.isPresent()) {
                attrs.put("result", "FAIL");
                attrs.put("score", String.valueOf(reportJson.get().total()));
                return;
            }
            // 旧版 .md 兜底（v3 存量会话目录）
            Path dir = taskWorkspaceService.getTaskDir(userId, sessionId);
            Path passed = dir.resolve(TaskWorkspace.FILE_REVIEW_PASSED_MD);
            Path report = dir.resolve(TaskWorkspace.FILE_REVIEW_REPORT_MD);
            Path file = Files.exists(passed) ? passed : (Files.exists(report) ? report : null);
            if (file == null) {
                return;
            }
            attrs.put("result", file == passed ? "PASS" : "FAIL");
            Matcher m = SCORE_PATTERN.matcher(Files.readString(file, StandardCharsets.UTF_8));
            if (m.find()) {
                attrs.put("score", m.group(1));
            }
        } catch (Exception e) {
            log.debug("读取评审产物失败（review-attempt 打点缺 result/score）: {}", e.getMessage());
        }
    }

    /** review_passed 产物是否存在（存在 = 质检已通过；S2.7 起 JSON，旧 .md 双读兜底） */
    private boolean reviewPassedFileExists(String userId, String sessionId) {
        try {
            Path dir = taskWorkspaceService.getTaskDir(userId, sessionId);
            return Files.exists(dir.resolve(TaskWorkspace.FILE_REVIEW_PASSED))
                    || Files.exists(dir.resolve(TaskWorkspace.FILE_REVIEW_PASSED_MD));
        } catch (Exception e) {
            log.debug("检查 review_passed 产物失败（按不存在处理）: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 其余阶段透传 ====================

    @Override
    public int order() {
        // PlanningGateMiddleware(-1000) 内层；SubagentsMiddleware(默认 order=1) 外层
        return -900;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    java.util.function.Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        java.util.function.Function<ReasoningInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        java.util.function.Function<ModelCallInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }
}
