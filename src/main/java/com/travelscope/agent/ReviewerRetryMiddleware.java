package com.travelscope.agent;

import com.travelscope.config.AgentConfig.TaskWorkspaceService;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reviewer 回炉保险丝（FR-S08 / FR-U09：质检不通过回炉 ≤2 次，超限保护）
 * <p>
 * <b>设计决策（2026-09-19，与原需求字面方案的取舍）</b>：原需求写「拦截 master 的
 * 返回用户回复替换为继续委派 Planner」——反编译确认框架没有改写文本回复的中间件钩子
 * （MiddlewareBase 无 onReply；onReasoning 只能透传/替换推理输入，无法终止已产出的文本）。
 * 因此采用项目已验证的「提示词驱动 + 代码层保险丝」模式（与 PlanningGate/四维校验/
 * 同步强制一致）：回炉循环由 planner 提示词驱动（读 review_report 改进建议→修订→重送
 * ≤2 次），本中间件只在代码层拦住「第 3+ 次送审」——把该次 agent_spawn(reviewer-agent)
 * 改写为 {@code review_retry_hint} 内部工具（同 PlanningGate 的 GATE 模式），返回
 * 「已达回炉上限，按当前最佳版本汇报」——从代码层杜绝无限循环，不依赖模型自觉。
 * </p>
 * <p>
 * 计数规则：每次 spawn reviewer-agent 计 1 次（含首次送审）；review_passed.md 出现
 * 即重置计数（通过后不再有送审）。检测标准 4 的日志锚点：{@code review_retry count=N}。
 * </p>
 */
public class ReviewerRetryMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ReviewerRetryMiddleware.class);

    /** 回炉上限（FR-S08 / FR-U09：首次送审 + 最多 2 次回炉重送 = 最多 3 次 spawn） */
    public static final int MAX_REVIEW_RETRIES = 2;

    /** 送审次数上限（首审 + 回炉上限） */
    public static final int MAX_SPAWN_ATTEMPTS = 1 + MAX_REVIEW_RETRIES;

    /** 被拦截时替换执行的工具名（必须在 Toolkit 中真实注册，见 TaskTools.reviewRetryHint） */
    public static final String RETRY_HINT_TOOL = "review_retry_hint";

    private final TaskWorkspaceService taskWorkspaceService;

    /** userId:sessionId → 送审计数（跨轮持久于进程内；会话粒度隔离） */
    private final Map<String, AtomicInteger> spawnCounts = new ConcurrentHashMap<>();

    public ReviewerRetryMiddleware(TaskWorkspaceService taskWorkspaceService) {
        this.taskWorkspaceService = taskWorkspaceService;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     java.util.function.Function<ActingInput, Flux<AgentEvent>> next) {
        // 只关心 spawn reviewer-agent 的调用
        boolean hasReviewerSpawn = input.toolCalls().stream()
                .anyMatch(t -> "agent_spawn".equals(t.getName())
                        && ReviewerAgent.AGENT_NAME.equals(agentIdOf(t)));
        if (!hasReviewerSpawn) {
            return next.apply(input);
        }

        String userId = ctx.getUserId();
        String sessionId = ctx.getSessionId();

        // 质检已通过 → 重置计数放行（防御：通过后不应再送审，若发生按首审处理）
        if (reviewPassedFileExists(userId, sessionId)) {
            spawnCounts.remove(containerKey(userId, sessionId));
            log.info("review_passed 已存在，回炉计数重置: 用户={}, 会话={}", userId, sessionId);
            return next.apply(input);
        }

        int attempt = spawnCounts.computeIfAbsent(containerKey(userId, sessionId),
                k -> new AtomicInteger(0)).incrementAndGet();

        // 未超限（首审或前两次回炉）→ 放行并留痕
        if (attempt <= MAX_SPAWN_ATTEMPTS) {
            log.info("review_retry count={}（{}） 用户={}, 会话={}",
                    attempt, attempt == 1 ? "首次送审" : "第 " + (attempt - 1) + " 次回炉重送",
                    userId, sessionId);
            return next.apply(input);
        }

        // 超限：第 4+ 次尝试送审 → 改写为 hint 工具，reviewer 不再执行
        log.warn("review_retry 保险丝拦截: 送审尝试 {} 次超过上限 {}（回炉 {} 次），"
                        + "本次 agent_spawn 已替换为 {}，用户={}, 会话={}",
                attempt, MAX_SPAWN_ATTEMPTS, MAX_REVIEW_RETRIES, RETRY_HINT_TOOL, userId, sessionId);
        String hint = "RETRY_LIMIT_REACHED: 质检送审已达上限（首审 + " + MAX_REVIEW_RETRIES
                + " 次回炉），本次送审已被系统拦截未执行。"
                + "请立即停止送审，按当前最佳版本的 itinerary_draft.md 汇报收尾："
                + "汇报首行标注「⚠️ 当前最佳版本（已尽力，评分 x/100）」"
                + "（x 取最近一次 review_report.md 的总分），列出未解决的扣分项，"
                + "并调用 register_task_result(taskType=itinerary) 登记当前版本。";
        List<ToolUseBlock> rewritten = new ArrayList<>();
        for (ToolUseBlock call : input.toolCalls()) {
            if ("agent_spawn".equals(call.getName())
                    && ReviewerAgent.AGENT_NAME.equals(agentIdOf(call))) {
                rewritten.add(new ToolUseBlock(call.getId(), RETRY_HINT_TOOL,
                        Map.of("reason", hint)));
            } else {
                rewritten.add(call);
            }
        }
        return next.apply(new ActingInput(rewritten));
    }

    private static String containerKey(String userId, String sessionId) {
        return userId + ":" + sessionId;
    }

    /** review_passed.md 是否存在（存在 = 质检已通过） */
    private boolean reviewPassedFileExists(String userId, String sessionId) {
        try {
            Path dir = taskWorkspaceService.getTaskDir(userId, sessionId);
            return Files.exists(dir.resolve(TaskWorkspaceService.FILE_REVIEW_PASSED));
        } catch (Exception e) {
            log.debug("检查 review_passed.md 失败（按不存在处理）: {}", e.getMessage());
            return false;
        }
    }

    /** 读取 agent_spawn 调用参数中的目标 agent_id */
    private String agentIdOf(ToolUseBlock call) {
        Object agentId = call.getInput() != null ? call.getInput().get("agent_id") : null;
        return agentId != null ? String.valueOf(agentId) : null;
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
