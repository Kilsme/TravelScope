package com.travelscope.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.travelscope.agent.IntentCascadeRouter;
import com.travelscope.agent.IntentRouterMiddleware;
import com.travelscope.agent.IntentType;
import com.travelscope.agent.ItineraryAgent;
import com.travelscope.agent.TravelMasterAgent;
import com.travelscope.common.ActivityPresenter;
import com.travelscope.common.TracingHelper;
import com.travelscope.dto.ChatEvent;
import com.travelscope.dto.IntentResult;
import com.travelscope.dto.TripRequirementState;
import com.travelscope.entity.Conversation;
import com.travelscope.entity.Message;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.travelscope.collab.TaskWorkspace;
import com.travelscope.collab.IntakeResult;
import com.travelscope.collab.TaskBacklog;

/**
 * 对话编排服务
 * <p>
 * 核心流程（应用层意图路由，参考 AgentScope 官方 Application-Layer 编排模式）：
 * <pre>
 * 用户消息 → 意图分类 Agent（结构化输出）
 *   ├─ CHAT / TOOL_CALL / RAG → 主 Agent + 路由指令（IntentRouterMiddleware 禁止委派）
 *   ├─ RAG（知识库可用时）    → 先检索知识片段拼入上下文再回答
 *   └─ PLANNING              → 代码五步编排（S2.5）：采集 workflow → 登记任务清单 →
 *                              直持 planning-agent 执行 → 渲染收尾（见 planningOrchestration）;
 *                              form 开关关闭 / workflow 异常时落回 v3 master 规划链兜底
 * </pre>
 * 事件流通过 SseEmitter 推送给前端；意图分类（阻塞调用）与 Agent 执行均调度到
 * boundedElastic 线程池，不占用容器线程。
 * </p>
 */
@Service
public class ChatService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.travelscope.assessment.ObservationStore observationStore;
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("assessmentMasterAgent")
    private org.springframework.beans.factory.ObjectProvider<HarnessAgent> assessmentAgents;
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("assessmentPlanningAgent")
    private org.springframework.beans.factory.ObjectProvider<HarnessAgent> assessmentPlanners;
    public HarnessAgent newAssessmentAgent(){return assessmentAgents.getObject();}
    public boolean assessmentWaiting(long identity){
        var state=tripRequirementStore.get(""+identity,SESSION_PREFIX+identity);
        return state!=null&&state.status==TripRequirementState.Status.COLLECTING;
    }


    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** AgentScope 会话 ID 前缀（与 conversations 表 ID 关联，保证多轮上下文） */
    private static final String SESSION_PREFIX = "conv-";

    /**
     * RuntimeContext 协作目录键（S2.10 起收编为本类私有——原 IntentRouterMiddleware
     * 的常量随中间件简化删除）。PoiRagTools / RequirementTools 的 resolveSessionId
     * 在本键兜底解析主会话 ID（模型传错 sessionId 时的纠偏），注入不可停。
     */
    private static final String CTX_COLLAB_DIR_KEY = "travelscope.collab.dir";

    private final HarnessAgent travelMasterAgent;
    private final IntentCascadeRouter intentCascadeRouter;
    private final RagService ragService;
    private final ConversationService conversationService;
    private final TaskWorkspace taskWorkspaceService;
    private final TripRequirementStore tripRequirementStore;
    private final LlmGateway llmGateway;
    /** RAG 检索最终条数（travelscope.rag.context-top-k，原 top-k/硬编码 5；召回扇出走 rag.recall-fanout） */
    private final int ragTopK;
    /** 对话历史渲染器（travelscope.chat-history.*；enabled=false 时 render 返回空串） */
    private final ChatHistoryRenderer chatHistoryRenderer;
    private final boolean historyEnabled;
    /** 会话记忆摘要（2026-09-29 失忆修复：每满 10 轮后台增量折叠，随窗口一起注入） */
    private final ConversationSummaryService conversationSummaryService;
    /** 意图分类上下文构建器（travelscope.intent-cascade.context-*，失忆修复 Fix 4） */
    private final IntentContextBuilder intentContextBuilder;
    private final boolean intentContextEnabled;
    /** 业务链路打点（chat-turn span：一轮对话的 trace 根，2026-10-02） */
    private final TracingHelper tracing;
    /** 应用配置（泳道超时等运行时读取） */
    private final com.travelscope.config.AppProperties appProperties;
    /** 过载降级（2026-09-23 并发改造：GREEN/YELLOW/RED 三级，意图分类后准入） */
    private final LoadShedService loadShedService;
    /** 积分制度（2026-10-11）：意图分类后按档预扣（普通 50 / 规划 200，余额不足拒绝） */
    private final PointsService pointsService;
    /** 装饰后主链路模型 Bean（P1-K：persistReply 读 getModelName() 记当轮实际生效模型——
     *  粘滞切换下 fallback 轮返回 fallback 名，修复 Token 报表固定写主链路模型的记错对象） */
    private final Model gatewayChatModel;
    /** 需求采集 workflow（S2.3 清单卡片桥接；S2.5 起成为 PLANNING 编排主入口） */
    private final com.travelscope.service.workflow.RequirementWorkflowService requirementWorkflowService;
    /** 任务拆分器（S2.4：代码模板确定性生成 task_backlog.json，零 LLM） */
    private final com.travelscope.service.workflow.TaskBacklogPlanner taskBacklogPlanner;
    /** S2.5 PLANNING 编排直持的行程规划 Agent（方案 B 决策：编排/渲染收归代码、不经 master
     *  中转——LLM 不确定性收敛到 planner 的执行本职；master 兜底链（v3）原样保留） */
    private final HarnessAgent itineraryPlanningAgent;

    public ChatService(@org.springframework.beans.factory.annotation.Qualifier("travelMasterAgent") HarnessAgent travelMasterAgent,
                       IntentCascadeRouter intentCascadeRouter,
                       RagService ragService,
                       ConversationService conversationService,
                       TaskWorkspace taskWorkspaceService,
                       TripRequirementStore tripRequirementStore,
                       LlmGateway llmGateway,
                       LoadShedService loadShedService,
                       ConversationSummaryService conversationSummaryService,
                       TracingHelper tracing,
                       com.travelscope.config.AppProperties appProperties,
                       Model gatewayChatModel,
                       PointsService pointsService,
                       com.travelscope.service.workflow.RequirementWorkflowService requirementWorkflowService,
                       com.travelscope.service.workflow.TaskBacklogPlanner taskBacklogPlanner,
                       @org.springframework.beans.factory.annotation.Qualifier("itineraryPlanningAgent")
                       HarnessAgent itineraryPlanningAgent) {
        this.travelMasterAgent = travelMasterAgent;
        this.intentCascadeRouter = intentCascadeRouter;
        this.ragService = ragService;
        this.conversationService = conversationService;
        this.taskWorkspaceService = taskWorkspaceService;
        this.tripRequirementStore = tripRequirementStore;
        this.llmGateway = llmGateway;
        this.ragTopK = appProperties.getRag().getContextTopK();
        this.chatHistoryRenderer = new ChatHistoryRenderer(
                appProperties.getChatHistory().getRounds(),
                appProperties.getChatHistory().getMaxCharsPerMessage(),
                appProperties.getChatHistory().getSummary().getMaxChars());
        this.historyEnabled = appProperties.getChatHistory().isEnabled();
        this.appProperties = appProperties;
        this.loadShedService = loadShedService;
        this.conversationSummaryService = conversationSummaryService;
        this.intentContextBuilder = new IntentContextBuilder(
                appProperties.getIntentCascade().getContextRounds(),
                appProperties.getIntentCascade().getContextMaxCharsPerMessage(),
                appProperties.getIntentCascade().getContextSummaryMaxChars());
        this.intentContextEnabled = appProperties.getIntentCascade().isContextEnabled();
        this.tracing = tracing;
        this.gatewayChatModel = gatewayChatModel;
        this.pointsService = pointsService;
        this.requirementWorkflowService = requirementWorkflowService;
        this.taskBacklogPlanner = taskBacklogPlanner;
        this.itineraryPlanningAgent = itineraryPlanningAgent;
    }

    /** 清单卡片结构化提交识别（JSON 对象且含 __form__ 键；与 workflow 的解析口径一致） */
    private static boolean isFormSubmission(String userMessage) {
        if (userMessage == null) {
            return false;
        }
        String trimmed = userMessage.trim();
        if (!trimmed.startsWith("{")) {
            return false;
        }
        try {
            com.alibaba.fastjson2.JSONObject object =
                    com.alibaba.fastjson2.JSON.parseObject(trimmed);
            return object != null && object.getJSONObject("__form__") != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * PLANNING 五步编排（S2.5：编排收归代码，替代 master 的 LLM 拆任务/委派/整合）：
     * <ol>
     *   <li><b>step1 需求采集</b>：workflow.handle——NEED_USER_INPUT → 推 requirement_form
     *       事件本轮结束；CANCELLED → 复位结束；COLLECTED（含 COLLECTING 期间文本增量后
     *       重推卡片，S2.2 分支 b 由 workflow 内部处理）→ step2</li>
     *   <li><b>step2 任务登记</b>：读 intake_result.json → taskBacklogPlanner.plan 代码模板
     *       生成并登记 task_backlog.json（S2.4，零 LLM）→ 显式 agent_status 里程碑
     *       （卡片点 4：活动流由编排代码发射，不再依赖主 Agent 文本分流）</li>
     *   <li><b>step3 委派规划</b>：直持 itineraryPlanningAgent 发起（方案 B 决策，见字段注释）——
     *       CTX_FORCE_SYNC/600s 平移自原 runAgent；确定性指令消息给出 JSON 契约路径
     *       （planner 提示词中的 .md 路径差异由 S2.6 提示词重写收口）</li>
     *   <li><b>step4 等待完成</b>：共用管线 600s 泳道超时；超时按 wait_async_results 等价
     *       语义截断（直持模式无异步提升），转 step5 尽力渲染</li>
     *   <li><b>step5 渲染收尾</b>：读 itinerary_draft.md（缺则 execution_result.md）+ 代码生成
     *       质检标注（review_passed.md 存在 → ✅ 已通过质量审阅；否则 → ⚠️ 当前最佳版本
     *       （已尽力），文案规则同 v3）；review_report 事件由 handleComplete 的标记驱动
     *       逻辑发出（标注文本自带「已通过质量审阅/已尽力」标记），handleComplete 零改动</li>
     * </ol>
     * 返回 {@code null} = form 开关关闭或 workflow 异常 → 落回 runAgent 的 v3 master
     * 规划链（兜底路径，其硬注入/IntentRouterMiddleware 路由指令原样保留）。
     */
    private Flux<ChatEvent> planningOrchestration(Conversation conversation, Long userId,
                                                  String userMessage, StringBuilder replyBuf,
                                                  StringBuilder thinkingFull,
                                                  TurnUsageTracker usageTracker) {
        return planningOrchestration(conversation, userId, userMessage, replyBuf,
                thinkingFull, usageTracker, null, "LIVE", false, null, itineraryPlanningAgent);
    }

    private Flux<ChatEvent> planningOrchestration(Conversation conversation, Long userId,
            String userMessage, StringBuilder replyBuf, StringBuilder thinkingFull,
            TurnUsageTracker usageTracker, com.travelscope.assessment.ObservationContext observation,
            String toolMode, boolean failTools, String fixtureCase, HarnessAgent activePlanner) {
        if (!appProperties.getRequirement().isFormEnabled()) {
            return null;
        }
        String userIdStr = String.valueOf(userId);
        String sessionId = SESSION_PREFIX + conversation.getId();
        // ---------- step1：需求采集 workflow ----------
        com.travelscope.service.workflow.dto.CollectOutcome outcome;
        try {
            outcome = "FIXTURE".equals(toolMode)
                    ? requirementWorkflowService.handle(userMessage, userIdStr, sessionId, observation,
                            com.travelscope.assessment.FixtureServices.transport())
                    : observation == null
                    ? requirementWorkflowService.handle(userMessage, userIdStr, sessionId)
                    : requirementWorkflowService.handle(userMessage, userIdStr, sessionId, observation);
        } catch (Exception e) {
            log.warn("需求采集 workflow 异常（降级走 v3 规划链）: 会话={}, 原因={}",
                    sessionId, e.getMessage());
            return null;
        }
        switch (outcome.status()) {
            case NEED_USER_INPUT -> {
                replyBuf.append("请确认以下行程需求，可直接修改后开始规划。");
                log.info("需求采集待输入（清单卡片已推送）: 会话={}, 缺失={}", sessionId,
                        outcome.formSpec().stream().filter(f -> !f.filled()).map(f -> f.name()).toList());
                return Flux.just(ChatEvent.of(ChatEvent.TYPE_REQUIREMENT_FORM,
                        toRequirementFormPayload(outcome)));
            }
            case CANCELLED -> {
                replyBuf.append("已取消规划。");
                log.info("需求采集已取消（状态机复位）: 会话={}", sessionId);
                return Flux.empty();
            }
            case COLLECTED -> {
                // 续走 step2~step5
            }
            default -> {
                return null;
            }
        }

        // ---------- step2：代码模板登记任务清单（intake_result.json 缺失 = workflow 契约破裂） ----------
        IntakeResult intake = taskWorkspaceService.readIntakeResult(userIdStr, sessionId)
                .orElseThrow(() -> new IllegalStateException(
                        "COLLECTED 后 intake_result.json 缺失（workflow 契约破裂）: " + sessionId));
        TaskBacklog backlog = taskBacklogPlanner.plan(userIdStr, intake);
        log.info("PLANNING 编排: 会话={}, 任务清单已登记 {} 项，开始委派 planning-agent",
                sessionId, backlog.tasks().size());

        // ---------- step3：直持 planning-agent 发起委派 ----------
        // CTX_FORCE_SYNC/600s 平移自原 runAgent（校准史见其注释）——子 Agent spawn 同步等待上限
        RuntimeContext ctx = RuntimeContext.builder()
                .userId(userIdStr)
                .sessionId(sessionId)
                .build();
        associateExecution(ctx, activePlanner, observation, toolMode, failTools, fixtureCase);
        if (observation != null) observationStore.task(observation, IntentType.PLANNING.name());
        ctx.put(AgentSpawnTool.CTX_FORCE_SYNC, Boolean.TRUE);
        ctx.put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 600);
        String collabDir = taskWorkspaceService.collabDirRelativePath(sessionId);
        // 协作目录键同注入（planner 侧缓存/任务状态工具的 resolveSessionId 兜底，与 master 链一致）
        ctx.put(CTX_COLLAB_DIR_KEY, collabDir);
        // 清单卡片结构化提交的原文是 JSON 控制报文，不带入任务说明（intake_result.json 已承载事实）
        String userContext = isFormSubmission(userMessage) ? "" : "\n\n【本轮用户消息】" + userMessage;
        String task = "【系统指令】需求与任务清单已就绪（已由系统登记进任务容器，禁止重新拆分或自行创建清单）。"
                + "用 read_file 读取 " + collabDir + "/task_backlog.json 与 " + collabDir + "/intake_result.json 执行"
                + "（任务按 T1→T5 顺序、P0→P1 优先级执行）；"
                + "产出 " + collabDir + "/itinerary_draft.json 后按流程送审。"
                + "协作目录: " + collabDir + "；sessionId: " + sessionId + userContext;
        UserMessage msg = new UserMessage(task);
        Duration laneTimeout = llmGateway.laneTimeout(true);

        // ---------- step4：等待完成（600s 泳道；超时按 wait_async_results 等价语义截断） ----------
        Flux<ChatEvent> plannerFlow = mapAgentEventStream(activePlanner.streamEvents(msg, ctx),
                        ItineraryAgent.AGENT_NAME, conversation, IntentType.PLANNING, true, laneTimeout,
                        replyBuf, thinkingFull, usageTracker, observation)
                .onErrorResume(java.util.concurrent.TimeoutException.class, e -> {
                    log.warn("PLANNING 编排泳道超时（{}s），转 step5 尽力渲染: 会话={}",
                            laneTimeout.toSeconds(), sessionId);
                    return Flux.empty();
                });
        return Flux.concat(
                Flux.just(milestoneStatus("任务清单已登记（" + backlog.tasks().size() + " 项），开始行程规划")),
                plannerFlow,
                // ---------- step5：渲染收尾（replyBuf 注入最终方案 + 质检标注） ----------
                Flux.defer(() -> {
                    renderPlanningResult(userIdStr, sessionId, replyBuf);
                    return Flux.empty();
                }));
    }

    /**
     * step5 渲染收尾（编排路径）：读协作目录方案文件 → 追加代码生成的质检标注写入 replyBuf。
     * review_report 事件不在此发——handleComplete 的标记驱动逻辑（回复含「已通过质量审阅」/
     * 「已尽力」→ 读对应 review 文件推送）对标注文本天然生效。无产物文件时保持 replyBuf
     * 原样（handleComplete 的 thinkingFull 兜底与空回复语义不变）。
     */
    private void renderPlanningResult(String userId, String sessionId, StringBuilder replyBuf) {
        String plan = assemblePlanningReply(userId, sessionId);
        if (plan == null) {
            log.warn("PLANNING 编排 step5: 无方案产物文件（规划中途失败/超时截断），保持原回复: 会话={}", sessionId);
            return;
        }
        replyBuf.setLength(0);
        replyBuf.append(plan).append(reviewAnnotation(userId, sessionId));
    }

    /**
     * 质检标注（代码版 v3 文案规则）：质检通过 →「✅ 已通过质量审阅（评分 x/100）」；
     * 未通过（回炉超限）→「⚠️ 当前最佳版本（已尽力，评分 x/100）」；未走到质检 → 空串。
     * S2.7 起 JSON 契约优先（typed total）；旧 .md 产物正则兜底（v3 存量会话目录）。
     */
    private String reviewAnnotation(String userId, String sessionId) {
        try {
            java.util.Optional<com.travelscope.collab.ReviewPassed> passedJson =
                    taskWorkspaceService.readReviewPassed(userId, sessionId);
            if (passedJson.isPresent()) {
                return "\n\n✅ 已通过质量审阅（评分 " + passedJson.get().total() + "/100）";
            }
            java.util.Optional<com.travelscope.collab.ReviewReport> reportJson =
                    taskWorkspaceService.readReviewReport(userId, sessionId);
            if (reportJson.isPresent()) {
                return "\n\n⚠️ 当前最佳版本（已尽力，评分 " + reportJson.get().total() + "/100）";
            }
            // 旧版 .md 兜底
            java.nio.file.Path dir = taskWorkspaceService.getTaskDir(userId, sessionId);
            java.nio.file.Path passed = dir.resolve(TaskWorkspace.FILE_REVIEW_PASSED_MD);
            if (java.nio.file.Files.exists(passed)) {
                return "\n\n✅ 已通过质量审阅（评分 " + scoreOf(passed) + "/100）";
            }
            java.nio.file.Path report = dir.resolve(TaskWorkspace.FILE_REVIEW_REPORT_MD);
            if (java.nio.file.Files.exists(report)) {
                return "\n\n⚠️ 当前最佳版本（已尽力，评分 " + scoreOf(report) + "/100）";
            }
        } catch (Exception e) {
            log.warn("生成质检标注失败（方案不加标注）: {}", e.getMessage());
        }
        return "";
    }

    /** 评审产物总分提取（与 ReviewerRetryMiddleware 同款「总分: x/100」行；提取失败返回 "?"） */
    private static String scoreOf(java.nio.file.Path reviewFile) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("总分[:：]\\s*(\\d+)")
                    .matcher(java.nio.file.Files.readString(reviewFile, java.nio.charset.StandardCharsets.UTF_8));
            if (m.find()) {
                return m.group(1);
            }
        } catch (Exception ignored) {
            // 读取失败按 "?" 兜底
        }
        return "?";
    }

    /** 编排里程碑事件（S2.5 卡片点 4：活动流由编排代码显式发射，payload 形状与工具活动一致） */
    private static ChatEvent milestoneStatus(String action) {
        JSONObject payload = new JSONObject();
        payload.put("agent", ActivityPresenter.agentLabel(ItineraryAgent.AGENT_NAME));
        payload.put("action", action);
        payload.put("state", "RUNNING");
        return ChatEvent.of(ChatEvent.TYPE_AGENT_STATUS, payload.toJSONString());
    }

    /** 清单卡片事件载荷：{fields:[...], hint, errors:[...]}（包私有：载荷形状单测直接锚定） */
    static String toRequirementFormPayload(
            com.travelscope.service.workflow.dto.CollectOutcome outcome) {
        com.alibaba.fastjson2.JSONObject payload = new com.alibaba.fastjson2.JSONObject();
        payload.put("fields", outcome.formSpec());
        payload.put("hint", outcome.hint());
        payload.put("errors", outcome.errors());
        // WriteNulls：hint/value 为 null 时也保留键——前端按固定键集合解析（契约测试锁定）
        return payload.toJSONString(com.alibaba.fastjson2.JSONWriter.Feature.WriteNulls);
    }

    /**
     * 处理一轮对话：持久化用户消息 → LLM Gateway 准入 → 意图分类 → 路由执行 → SSE 推送 → 持久化回复
     *
     * @param conversation 会话（已校验归属）
     * @param userId       用户 ID
     * @param userMessage  用户消息原文
     * @return SSE 发射器
     */
    /** 快泳道专用调度池（FR-S09 线程池隔离）：查询类对话独立于慢泳道，规划的长阻塞不拖慢查询。
     *  2026-09-23 并发改造：10 → 32（2000 在线 / 200~300 并发对话基线；LoadShed 兜底极端情况） */
    private final reactor.core.scheduler.Scheduler fastLaneScheduler =
            reactor.core.scheduler.Schedulers.newBoundedElastic(32, 10_000, "fast-lane", 60, true);
    /** 慢泳道专用调度池：PLANNING 长任务（同步 spawn 阻塞等待子 Agent）在此排队。
     *  同上：10 → 32 */
    private final reactor.core.scheduler.Scheduler slowLaneScheduler =
            reactor.core.scheduler.Schedulers.newBoundedElastic(32, 10_000, "slow-lane", 60, true);

    public SseEmitter streamChat(Conversation conversation, Long userId, String userMessage) {
        conversationService.saveUserMessage(conversation, userId, userMessage);

        // SSE 超时（并发改造 2026-09-23）：初始即给慢泳道档（泳道 300s + 30s 缓冲）——
        // 此前 0L 永不超时，客户端异常断开时连接与缓冲泄漏（onTimeout 永不触发）。
        // Spring 6.0 SseEmitter 无运行时改超时 API（构造后不可变），快对话提前 complete
        // 即回收，330s 只是 PLANNING 轮的兜底上限
        SseEmitter emitter = new SseEmitter(Duration.ofSeconds(
                appProperties.getLlmGateway().getSlowLaneTimeoutSeconds() + 30).toMillis());
        StringBuilder replyBuf = new StringBuilder();
        // THINKING 全文副本（PLANNING 轮 master 当轮输出的兜底来源）——每轮局部变量，
        // 并发轮次互不干扰（2026-09-23 串话修复：原为单例实例字段，并发 PLANNING 轮互相覆盖）
        StringBuilder thinkingFull = new StringBuilder();
        // 当轮 token 用量累计器（P1-K 真实采集）：每轮局部变量，同 thinkingFull——事件流中
        // 的 ModelCallEndEvent 累加真实 usage，轮末经 persistReply 落库；并发轮次互不干扰
        String turnSessionKey = SESSION_PREFIX + conversation.getId();
        TracingHelper.SpanHandle turnSpan = tracing.startSpan("chat-turn", Map.of(
                "userId", String.valueOf(userId),
                "sessionId", turnSessionKey));
        tracing.beginTurn(String.valueOf(userId), turnSessionKey, turnSpan);

        TurnUsageTracker usageTracker = new TurnUsageTracker();
        var observation = observationStore != null && observationStore.enabled()
                ? observationStore.begin(SESSION_PREFIX + conversation.getId(), null, turnSpan.traceId()) : null;
        long observationStarted = System.nanoTime();
        java.util.concurrent.atomic.AtomicBoolean planningRequested=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<String> observationStatus = new java.util.concurrent.atomic.AtomicReference<>("REPLIED");

        // LLM Gateway 准入（FR-S09）：全局/单用户并发 + QPS；被拒 → SSE error 降级文案（不白屏）
        LlmGateway.AcquireResult admission = llmGateway.tryAcquire(String.valueOf(userId));
        if (!admission.allowed()) {
            turnSpan.end();
            if (observation != null) observationStore.finish(observation,"REJECTED",(System.nanoTime()-observationStarted)/1e6,false);
            try {
                emitter.send(SseEmitter.event().name(ChatEvent.TYPE_ERROR)
                        .data(admission.message()));
            } catch (Exception ignored) {
                // 客户端已断开
            }
            emitter.complete();
            return emitter;
        }

        // 业务打点（chat-turn，2026-10-02 多智能体链路 Trace）：本轮对话的 trace 根——
        // 请求线程开启并短暂 inScope（订阅点捕获 trace 上下文，经 Reactor 自动传播流入
        // 泳道/Agent 链，子 span 才挂得到父链）；intent 在分类后补标；doFinally 终态结束
        // （complete/error/cancel 全覆盖，客户端断开 dispose 也不漏 span）。
        // 另经 beginTurn 按会话登记——框架内部线程无 trace 上下文，工具/中间件打点经
        // TracingHelper.recordChildSpan 显式挂到本 span 下
        Disposable disposable;
        try (var spanScope = turnSpan.inScope()) {
            disposable = Mono
                    .fromCallable(() -> resolveIntent(conversation, userId, userMessage, observation))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(intent -> {
                        // 意图分类完成，补标 chat-turn 的 intent 属性（含 UNKNOWN 兜底）
                        turnSpan.tag("intent", intent != null && intent.toIntentType() != null
                                ? intent.toIntentType().name() : "UNKNOWN");
                        // 过载降级准入（2026-09-23）：意图明确后判定——YELLOW 只挡新 PLANNING
                        // （慢泳道内存大头），快请求继续服务；RED 挡全部新对话。
                        // 注意：此时网关槽位已持有，拒绝路径须 release
                        boolean planningIntent = intent != null && intent.toIntentType() == IntentType.PLANNING;
                        planningRequested.set(planningIntent);
                        // 积分制度（2026-10-11）：意图明确后按档预扣（普通对话/规划差异计费，
                        // 意图分类失败 null 按普通档）；余额不足 → 拒绝本轮。
                        // 注意：此时网关槽位已持有，拒绝路径须 release（与 loadShed 同款配对）
                        int turnCost = pointsService.costOf(planningIntent);
                        if (!pointsService.tryDeduct(userId, turnCost)) {
                            observationStatus.set("REJECTED");
                            llmGateway.release(String.valueOf(userId));
                            try {
                                emitter.send(SseEmitter.event()
                                        .name(ChatEvent.TYPE_INTENT).data(toIntentPayload(intent)));
                                emitter.send(SseEmitter.event().name(ChatEvent.TYPE_ERROR)
                                        .data("积分不足：本次对话需 " + turnCost
                                                + " 积分，请联系管理员发放后再试"));
                            } catch (Exception ignored) {
                                // 客户端已断开
                            }
                            emitter.complete();
                            return Flux.<ChatEvent>empty();
                        }
                        String shed = loadShedService.tryAdmit(planningIntent);
                        if (shed != null) {
                            observationStatus.set("REJECTED");
                            llmGateway.release(String.valueOf(userId));
                            try {
                                emitter.send(SseEmitter.event()
                                        .name(ChatEvent.TYPE_INTENT).data(toIntentPayload(intent)));
                                emitter.send(SseEmitter.event().name(ChatEvent.TYPE_ERROR).data(shed));
                            } catch (Exception ignored) {
                                // 客户端已断开
                            }
                            emitter.complete();
                            return Flux.<ChatEvent>empty();
                        }
                        // S2.5 PLANNING 五步编排（编排收归代码）：待输入/取消本轮结束；
                        // 收齐 → 登记→委派→等待→渲染全代码驱动；
                        // 返回 null（form 开关关闭 / workflow 异常）落回 v3 master 规划链兜底
                        Flux<ChatEvent> planningFlow = planningIntent
                                ? planningOrchestration(conversation, userId, userMessage, replyBuf,
                                        thinkingFull, usageTracker, observation, "LIVE", false, null,
                                        itineraryPlanningAgent)
                                : null;
                        return Flux.concat(
                            Flux.just(toIntentEvent(intent)),
                            planningFlow != null
                                    ? planningFlow
                                    : runAgent(conversation, userId, userMessage, intent, replyBuf,
                                            thinkingFull, usageTracker, observation))
                            .doOnNext(event -> {
                                if (ChatEvent.TYPE_ERROR.equals(event.getType())) observationStatus.set("FAILED");
                            })
                            // 快慢泳道线程池隔离（FR-S09）：按意图把整条对话链（含 Agent 执行）
                            // 调度到各自泳道池——PLANNING 的同步 spawn 长阻塞只占慢池，
                            // 查询类在快池独立执行互不拖拽（最内层 subscribeOn 生效于 Agent 链源头）
                            .subscribeOn(intent != null && intent.toIntentType() == IntentType.PLANNING
                                    ? slowLaneScheduler : fastLaneScheduler);
                    })
                    // 终态（含取消/超时 dispose）结束 chat-turn span；会话父注册跨轮存续
                    // （TracingHelper.beginTurn 注释：异步 spawn 的后台子链晚到的打点仍挂本轮 trace）
                    .doOnError(error -> observationStatus.set("FAILED"))
                    .doFinally(signal -> {
                        turnSpan.end();
                        if (observation != null) {
                            boolean artifact = false;
                            String status = observationStatus.get();
                            if (signal == reactor.core.publisher.SignalType.CANCEL) status = "CANCELLED";
                            try {
                                artifact=observationStore.planningArtifact(observation)!=null;
                                if ("REPLIED".equals(status)) {
                                    var state = tripRequirementStore.get(String.valueOf(userId), SESSION_PREFIX + conversation.getId());
                                    if (planningRequested.get() && state != null && state.status == TripRequirementState.Status.COLLECTING) status = "WAITING_INPUT";
                                    else if (artifact) status = "PLANNING_COMPLETED";
                                    else if(planningRequested.get())status="INCOMPLETE";
                                }
                            } catch (Exception ignored) { status = "INCOMPLETE"; }
                            observationStore.finish(observation,status,(System.nanoTime()-observationStarted)/1e6,artifact);
                        }
                    })
                    .subscribe(
                            event -> sendEvent(emitter, event),
                            error -> handleError(emitter, replyBuf, conversation, userId, error, usageTracker, observation),
                            () -> handleComplete(emitter, replyBuf, conversation, userId, thinkingFull, usageTracker, observation));
        }

        emitter.onTimeout(disposable::dispose);
        emitter.onError(t -> disposable.dispose());
        emitter.onCompletion(disposable::dispose);
        return emitter;
    }

    /**
     * 意图分类（阻塞调用，运行在 boundedElastic；FR-S01 三层级联入口）
     * <p>
     * L0 会话延续 → L1 规则表 → L2 轻量模型（deepseek-v4-flash-0731）→ L3 IntentClassifier 兜底，
     * 上层命中即短路（见 IntentCascadeRouter）；日志格式 cascade_hit=L{n} latency=xms。
     *
     * @return 分类结果；失败返回 null（主 Agent 按自身提示词自主路由，不注入指令）
     */
    private IntentResult resolveIntent(Conversation conversation, Long userId, String userMessage) {
        return resolveIntent(conversation, userId, userMessage, null);
    }

    private IntentResult resolveIntent(Conversation conversation, Long userId, String userMessage,
            com.travelscope.assessment.ObservationContext observation) {
        return resolveIntent(conversation, userId, userMessage, observation, true);
    }

    private IntentResult resolveIntent(Conversation conversation, Long userId, String userMessage,
            com.travelscope.assessment.ObservationContext observation, boolean includePersonalHistory) {
        // 清单卡片提交（S2.3）：{"__form__":{...}} 结构化消息恒走 PLANNING 编排——
        // 它是界面动作而非自然语言，交给意图分类器会被判成 CHAT（实测 cancel 落到
        // master 被当成普通闲聊回复），必须在此短路
        if (isFormSubmission(userMessage)) {
            log.info("意图=PLANNING（清单卡片结构化提交，跳过意图分类）: 会话={}",
                    SESSION_PREFIX + conversation.getId());
            return new com.travelscope.dto.IntentResult(IntentType.PLANNING.name(),
                    "清单卡片结构化提交");
        }
        // 意图分类上下文注入（失忆修复 Fix 4）：messages 表 + 记忆摘要构建上下文块随消息
        // 送 L2/L3——「长春」这类对 intake 反问的裸名词短回答在语境下判 PLANNING，不再
        // 被判 CHAT 脱离规划流。messages 表是唯一可靠事实源（L0/状态机/缓存都是加速层，
        // 丢失可由此兜回）；效果依赖 POST /chat/stream 回传正确 conversationId。
        String contextBlock = "";
        if (intentContextEnabled && includePersonalHistory) {
            List<Message> messages = conversationService.listMessages(conversation.getId());
            String summary = appProperties.getChatHistory().getSummary().isEnabled()
                    ? conversation.getSummary() : null;
            contextBlock = intentContextBuilder.build(messages, summary);
        }
        IntentResult result = observation == null
                ? intentCascadeRouter.classify(userMessage, contextBlock, String.valueOf(userId),
                        SESSION_PREFIX + conversation.getId())
                : intentCascadeRouter.classify(userMessage, contextBlock, String.valueOf(userId),
                        SESSION_PREFIX + conversation.getId(), observation);
        if (result != null && result.toIntentType() == IntentType.RAG) {
            log.info("意图=RAG，知识库可用: {}（不可用时由路由指令回退为直接回答）", ragService.isAvailable());
        }
        // 收集期意图保护（失忆修复 Fix 3）：intake 正在反问等回答时，用户的短回答
        // （如「长春」「三天」）不含 L0 延续词、单条消息 L2 也判不出 PLANNING → 被判 CHAT
        // → 脱离规划流（master 只回通用文本）。这里在代码层覆盖：需求状态机处于 COLLECTING
        // 且分类为 CHAT → 强制 PLANNING（回流 intake 更新状态机）。TOOL_CALL/RAG 不覆盖
        // （用户明确要查天气/查票时正常放行）。Fix 4 上下文注入后本保护降级为第二道防线
        // （状态机是加速层，Redis 丢失时 Fix 4 的 messages+摘要路径仍可靠）。
        if (result != null && result.toIntentType() == IntentType.CHAT) {
            String reqSessionId = SESSION_PREFIX + conversation.getId();
            TripRequirementState state = tripRequirementStore.get(String.valueOf(userId), reqSessionId);
            // COLLECTING 且已开始收集（有任一已收集字段或已反问过）才覆盖——
            // 全新会话的真闲聊不应被拉进规划流
            boolean collecting = state != null
                    && state.status == TripRequirementState.Status.COLLECTING
                    && (state.missingFields().size() < TripRequirementState.REQUIRED_FIELDS.size()
                    || state.clarifyCycles > 0);
            if (collecting) {
                log.info("intent_override=PLANNING reason=requirement_collecting 会话={} 原判定=CHAT"
                                + "（已收集: {}，反问第 {} 轮）", reqSessionId,
                        state.collectedDescription(), state.clarifyCycles);
                return new com.travelscope.dto.IntentResult(IntentType.PLANNING.name(),
                        "收集期意图保护：需求收集进行中，短回答按 PLANNING 处理");
            }
        }
        return result;
    }

    /**
     * 运行主 Agent 并把事件流映射为 SSE 事件
     */
    private Flux<ChatEvent> runAgent(Conversation conversation, Long userId, String userMessage,
                                     IntentResult intent, StringBuilder replyBuf, StringBuilder thinkingFull,
                                     TurnUsageTracker usageTracker, com.travelscope.assessment.ObservationContext observation) {
        return runAgent(conversation,userId,userMessage,intent,replyBuf,thinkingFull,usageTracker,observation,"LIVE",false,null,travelMasterAgent);
    }
    private Flux<ChatEvent> runAgent(Conversation conversation,Long userId,String userMessage,IntentResult intent,
            StringBuilder replyBuf,StringBuilder thinkingFull,TurnUsageTracker usageTracker,
            com.travelscope.assessment.ObservationContext observation,String toolMode,boolean failTools,String fixtureCase,HarnessAgent activeAgent) {
        RuntimeContext ctx = RuntimeContext.builder()
                .userId(String.valueOf(userId))
                .sessionId(SESSION_PREFIX + conversation.getId())
                .build();
        associateExecution(ctx, activeAgent, observation, toolMode, failTools, fixtureCase);
        IntentType type = intent != null ? intent.toIntentType() : null;
        if (observation != null) observationStore.task(observation, type == null ? "UNKNOWN" : type.name());
        if (type != null) {
            ctx.put(IntentRouterMiddleware.CTX_INTENT_KEY, type.name());
        }
        // 需求 1：把本会话的隔离协作目录（相对路径 tasks/{sessionId}）注入上下文，
        // 供 PoiRagTools/RequirementTools 的 resolveSessionId 兜底解析主会话 ID
        //（S2.10 前还供 IntentRouterMiddleware 路由指令与委派门禁——两者已删）
        String agentSessionId = SESSION_PREFIX + conversation.getId();
        ctx.put(CTX_COLLAB_DIR_KEY,
                taskWorkspaceService.collabDirRelativePath(agentSessionId));
        // 前端可见性改造（2026-10-04，2026-10-05 模型网关迁移后再校准 420→600）：
        // PLANNING 轮强制 spawn 同步等待 600s——框架默认同步等待仅 30s；qwen 时期全链
        // 实测 275~320s 配 420s，迁移 glm-5-3-260814（thinking 禁用）+ RAG 语义路降级
        // （DashScope embedding 欠费）后实测 450s 仍未跑完质检段，600s 为框架 clamp 上限。
        // 双键经 AgentSpawnTool.resolveEffectiveTimeoutMs 生效（仅 force_sync 时读 ctx 键，
        // 上限 clamp 600s）；注意 master 自行传更小的显式 timeout_seconds 会覆盖本值
        // （提示词已改为显式要求 600）。
        if (type == IntentType.PLANNING) {
            ctx.put(AgentSpawnTool.CTX_FORCE_SYNC, Boolean.TRUE);
            ctx.put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 600);
        }

        String outgoing = userMessage;
        if (type == IntentType.RAG && !"FIXTURE".equals(toolMode) && ragService.isAvailable()) {
            // topK 走 RagConfig 配置（原硬编码 5）
            outgoing = augmentWithRag(userMessage,
                    ragService.retrieve(userMessage, ragTopK));
        }
        // v3 FR-S02 代码层兜底：PLANNING 且需求未收齐时，向本轮用户消息前置 intake 委派指令。
        // 模型对提示词的委派遵循性有波动（实测约 50% 轮次 master 只回文本不委派），
        // 应用层注入是硬保证——intake 收齐（DONE/DEGRADED）后不再注入。
        if (type == IntentType.PLANNING) {
            String agentSessionIdForReq = SESSION_PREFIX + conversation.getId();
            if (!tripRequirementStore.isCollected(String.valueOf(userId), agentSessionIdForReq)) {
                outgoing = "【系统指令】需求收集尚未完成，请立即调用 agent_spawn 委派 intake-agent"
                        + "（同步等待，不要传 timeout_seconds=0），任务说明中带上：本轮用户消息："
                        + userMessage + "；协作目录: " + taskWorkspaceService.collabDirRelativePath(agentSessionIdForReq)
                        + "；sessionId: " + agentSessionIdForReq
                        + "。禁止自己反问用户。\n\n【用户消息】" + userMessage;
                log.info("注入 intake 委派指令（需求未收齐）: 会话={}", agentSessionIdForReq);
            }
        }
        // 对话历史注入（失忆修复 Fix 2，全部意图生效）：记忆摘要 + 近 N 轮 messages 表历史前置——
        // 用户短回答（如「长春」）时 master 仍能看到之前聊过什么；同时兜底框架
        // InMemory 记忆在进程重启后丢失的场景。末条为本轮消息，render 内部已排除。
        // 2026-09-29：窗口 3 → 10 轮，窗口外更早轮次由滚动记忆摘要长期携带。
        if (historyEnabled && activeAgent == travelMasterAgent) {
            List<Message> messages = conversationService.listMessages(conversation.getId());
            // summary.enabled=false 时按配置退回纯窗口模式（不注入库里已有的摘要）
            String summary = appProperties.getChatHistory().getSummary().isEnabled()
                    ? conversation.getSummary() : null;
            String historyBlock = chatHistoryRenderer.render(messages, summary,
                    conversation.getSummaryCoveredMessages() == null ? 0 : conversation.getSummaryCoveredMessages());
            if (!historyBlock.isBlank()) {
                outgoing = historyBlock + "\n【本轮用户消息】\n" + outgoing;
            }
            // 缺口自愈：折叠失败/存量旧会话（covered 落后于窗口起点）时补折——
            // maybeSummarizeAsync 内部有到期判定与防重，异步不阻塞本轮
            conversationSummaryService.maybeSummarizeAsync(conversation.getId(), userId, observation);
        }

        UserMessage msg = new UserMessage(outgoing);
        // 快慢泳道（FR-S09）：PLANNING → 慢泳道 600s（yml 校准值）；其余（CHAT/TOOL_CALL/RAG/null）→ 快泳道。
        // 查询类不被进行中的长规划拖慢（独立链路 + 各自超时保护），超时 → SSE error 兜底文案
        Duration laneTimeout = llmGateway.laneTimeout(type == IntentType.PLANNING);
        boolean planning = type == IntentType.PLANNING;
        return mapAgentEventStream(activeAgent.streamEvents(msg, ctx),
                        TravelMasterAgent.AGENT_NAME, conversation, type, planning, laneTimeout,
                        replyBuf, thinkingFull, usageTracker, observation)
                .onErrorResume(java.util.concurrent.TimeoutException.class, e -> {
                    log.warn("泳道超时: 意图={}, 泳道={}s, conversationId={}",
                            type, laneTimeout.toSeconds(), conversation.getId());
                    return Flux.just(ChatEvent.of(ChatEvent.TYPE_ERROR,
                            "处理超时了（" + laneTimeout.toSeconds() + " 秒上限），请稍后重试或换个问法～"));
                });
    }

    /**
     * Agent 事件流 → SSE 事件流的共用映射管线（S2.5 抽取：runAgent 与 PLANNING 编排共用）：
     * token usage 累计 → 根/子事件过滤 → 文本/THINKING 分流 → 事件映射 → 泳道超时 →
     * doFinally 冲刷 THINKING 缓冲 → 非超时异常转 SSE error → 并入 THINKING 通道。
     * 超时异常向外传播由调用方处置（runAgent → 错误事件；PLANNING 编排 → 尽力渲染）。
     *
     * @param rootAgent 根 Agent 名（事件归属标签：runAgent=travel-master；编排=planning-agent）
     */
    private Flux<ChatEvent> mapAgentEventStream(Flux<AgentEvent> events, String rootAgent,
                                                Conversation conversation, IntentType type,
                                                boolean planning, Duration laneTimeout,
                                                StringBuilder replyBuf, StringBuilder thinkingFull,
                                                TurnUsageTracker usageTracker) {
        return mapAgentEventStream(events, rootAgent, conversation, type, planning, laneTimeout,
                replyBuf, thinkingFull, usageTracker, null);
    }

    private Flux<ChatEvent> mapAgentEventStream(Flux<AgentEvent> events, String rootAgent,
            Conversation conversation, IntentType type, boolean planning, Duration laneTimeout,
            StringBuilder replyBuf, StringBuilder thinkingFull, TurnUsageTracker usageTracker,
            com.travelscope.assessment.ObservationContext observation) {
        // THINKING 聚合句发射通道（accumulateThinking → Sinks.Many → mergeWith 回 SSE 流）：
        // 主流 doFinally 时 emitComplete——mergeWith 等两条流都完成才结束，Flux.create+轮询线程
        // 会永不 complete 导致 done 发不出（E2E 实测）；Sinks 无需独立线程且即时推送。
        // 全部为方法局部变量（2026-09-23 串话修复：原实例字段在并发 PLANNING 轮互相覆盖）
        reactor.core.publisher.Sinks.Many<ChatEvent> thinkingSink =
                reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();
        StringBuilder thinkingBuf = new StringBuilder();
        java.util.function.Consumer<String> thinkingEmitter = payload -> thinkingSink.tryEmitNext(
                ChatEvent.of(ChatEvent.TYPE_AGENT_STATUS, payload));
        long laneQueued = System.nanoTime();
        return events
                .doOnSubscribe(subscription -> { if (observation != null) observationStore.queue(observation,(System.nanoTime()-laneQueued)/1e6); })
                // Token 真实采集（P1-K，2026-10-11）：模型调用结束事件携带真实 ChatUsage，
                // 在过滤前挂点累计（ModelCallEnd 不是根 Agent 可见事件，过滤后会被滤掉）；
                // 每个 ModelCallEndEvent 对应一次模型调用（ReAct 多轮迭代各一次）
                .doOnNext(event -> {
                    if (event instanceof ModelCallEndEvent mce) {
                        usageTracker.onModelCallEnd(mce.getUsage());
                    }
                })
                // 根 Agent 自身事件 + intake 反问 + 子 Agent 工具活动（前端可见性改造：
                // 同步等待期间，子 Agent 的工具 Start/End 转为 agent_status 推送；
                // 子 Agent 文本 delta 仍被 isMainAgentEvent 拦截——思维链不外泄）
                .filter(event -> isMainAgentEvent(event) || isClarifyEvent(event)
                        || isSubAgentToolEvent(event))
                .doOnNext(event -> {
                    // 过程/结果分离（2026-09-22）：PLANNING 轮根 Agent 的过程文本不进 replyBuf——
                    // replyBuf 只保留给最终方案（done/入库），过程叙述按句聚合后进活动流
                    if (event instanceof TextBlockDeltaEvent e) {
                        if (!planning) {
                            replyBuf.append(e.getDelta());
                        } else {
                            accumulateThinking(rootAgent, e.getDelta(), thinkingBuf, thinkingFull,
                                    thinkingEmitter);
                        }
                    }
                    // intake 反问文本进 replyBuf：反问轮无最终方案文件，done/入库需要内容
                    if (event instanceof ToolResultTextDeltaEvent e && isClarifyEvent(e)) {
                        String delta = e.getDelta();
                        int cut = delta != null ? delta.indexOf(";;") : -1;
                        if (cut > 0) {
                            replyBuf.append(delta, 0, cut);
                        }
                    }
                })
                .mapNotNull(event -> toChatEvent(rootAgent, event, planning))
                .timeout(laneTimeout)
                .doFinally(signal -> {
                    // 残留缓冲 flush + 关闭 THINKING 通道（mergeWith 需两流都 complete）
                    if (planning && !thinkingBuf.isEmpty()) {
                        String rest = thinkingBuf.toString().strip();
                        thinkingBuf.setLength(0);
                        if (!rest.isEmpty()) {
                            emitThinking(rootAgent, rest, thinkingEmitter);
                        }
                    }
                    thinkingSink.tryEmitComplete();
                })
                .onErrorResume(e -> {
                    if (e instanceof java.util.concurrent.TimeoutException) {
                        if (observation != null) observationStore.timeout(observation);
                        // 超时交调用方处置：runAgent → 错误事件；PLANNING 编排 → 尽力渲染（S2.5）
                        return Flux.error(e);
                    }
                    log.error("Agent 执行异常: conversationId={}", conversation.getId(), e);
                    return Flux.just(ChatEvent.of(ChatEvent.TYPE_ERROR,
                            sseFallbackMessage(e)));
                })
                // THINKING 聚合句与主事件流合并（主流 complete 时 sink 同步关闭）
                .mergeWith(thinkingSink.asFlux());
    }

    private void associateExecution(RuntimeContext runtime, HarnessAgent agent,
            com.travelscope.assessment.ObservationContext observation, String mode,
            boolean failTools, String fixtureCase) {
        runtime.put("assessment.toolMode", mode);
        runtime.put("assessment.fixtureCase", fixtureCase);
        runtime.put("assessment.toolFailure", failTools);
        if (observation != null) {
            runtime.put(com.travelscope.assessment.ObservationContext.KEY, observation);
            runtime.put(com.travelscope.assessment.ArtifactSnapshotReader.FILESYSTEM_KEY,
                    agent.getWorkspaceManager().getFilesystem());
        }
    }

    /** 评测复用在线规划编排，但只使用独立身份和 Agent，不读写个人聊天或扣积分。 */
    public reactor.core.publisher.Mono<String> executeAssessment(long isolatedId, String message,
            com.travelscope.assessment.ObservationContext observation, String mode, boolean failTools,String fixtureCase,HarnessAgent isolatedAgent) {
        return Mono.defer(() -> {
            Conversation conversation = new Conversation();
            conversation.setId(isolatedId);
            conversation.setUserId(isolatedId);
            StringBuilder reply = new StringBuilder();
            StringBuilder thinking = new StringBuilder();
            TurnUsageTracker usage = new TurnUsageTracker();
            var intent = resolveIntent(conversation, isolatedId, message, observation, false);
            Flux<ChatEvent> execution = null;
            HarnessAgent planner = null;
            if (intent != null && intent.toIntentType() == IntentType.PLANNING
                    && appProperties.getRequirement().isFormEnabled()) {
                planner = assessmentPlanners.getObject();
                execution = planningOrchestration(conversation, isolatedId, message, reply, thinking,
                        usage, observation, mode, failTools, fixtureCase, planner);
            }
            if (execution == null) {
                execution = runAgent(conversation, isolatedId, message, intent, reply, thinking,
                        usage, observation, mode, failTools, fixtureCase, isolatedAgent);
            }
            HarnessAgent ownedPlanner = planner;
            return execution.doOnNext(event -> {
                        if (ChatEvent.TYPE_ERROR.equals(event.getType())) {
                            throw new IllegalStateException("Assessment execution failed");
                        }
                    })
                    .then(Mono.fromSupplier(reply::toString))
                    .doFinally(signal -> { if (ownedPlanner != null) ownedPlanner.close(); });
        });
    }

    /**
     * 只透传主 Agent 自身的事件（子代理转发的事件 source 含 "/"，其文本由主 Agent 整合后再输出）
     */
    private boolean isMainAgentEvent(AgentEvent event) {
        String source = event.getSource();
        return source == null || !source.contains("/");
    }

    /**
     * 反问事件（v3 FR-S02）：intake-agent 调 ask_user 工具的结果文本（source 含 "/" 的转发事件）。
     * <p>
     * 工具结果文本只出现在 ToolResultTextDeltaEvent（End 事件无内容 getter）。
     * ask_user 的返回值格式为「{反问};;[intake]{给 LLM 的轮次提示}」——SSE 层
     * 截取 {@code ;;} 之前的部分作为 clarify_question 事件推给前端。
     * 其他子代理转发事件（Start/End/delta 文本）仍被 isMainAgentEvent 拦截。
     * </p>
     */
    private boolean isClarifyEvent(AgentEvent event) {
        return event instanceof ToolResultTextDeltaEvent e
                && "ask_user".equals(e.getToolCallName())
                && e.getSource() != null && e.getSource().contains("/");
    }

    /**
     * 子 Agent 工具活动放行（2026-10-04 前端可见性改造）：source 含 "/"（嵌套子 Agent）
     * 的工具 Start/End 事件放行进 toChatEvent → agent_status，让用户全程看到
     * 「行程规划 · 检索景点 RUNNING」之类的活动，而不是委派后一片寂静。
     */
    private boolean isSubAgentToolEvent(AgentEvent event) {
        if (event.getSource() == null || !event.getSource().contains("/")) {
            return false;
        }
        return event instanceof ToolCallStartEvent || event instanceof ToolResultEndEvent;
    }

    /**
     * AgentEvent → SSE 事件映射（非推送事件返回 null 被过滤）
     * <p>
     * 过程/结果分离（2026-09-22 改造）：
     * <ul>
     *   <li>工具调用 → {@code agent_status} 事件（结构化 JSON：agent/action/state），
     *       前端以气泡下方小字活动流渲染，不再发 tool 裸字符串</li>
     *   <li>master 文本：非 PLANNING 意图 → delta（正文流式，旧行为）；
     *       PLANNING 意图 → agent_status 的 THINKING 摘要（过程叙述进活动流，正文留给最终方案）</li>
     *   <li>done 的最终方案文本在 handleComplete 生成（PLANNING 轮从协作目录整合）</li>
     * </ul>
     * </p>
     */
    private ChatEvent toChatEvent(String rootAgent, AgentEvent event, boolean planning) {
        if (event instanceof ToolResultTextDeltaEvent e && isClarifyEvent(e)) {
            // 截取 ;; 前的反问本身（其后是给 LLM 的轮次提示，不推给用户）
            String delta = e.getDelta();
            int cut = delta != null ? delta.indexOf(";;") : -1;
            String question = cut >= 0 ? delta.substring(0, cut) : delta;
            return question == null || question.isBlank()
                    ? null   // 轮次提示段（;; 之后）不推送
                    : ChatEvent.of(ChatEvent.TYPE_CLARIFY_QUESTION, question);
        }
        if (event instanceof TextBlockDeltaEvent e) {
            if (!planning) {
                return ChatEvent.of(ChatEvent.TYPE_DELTA, e.getDelta());
            }
            return null; // PLANNING 轮过程文本经 thinkingStatus 缓冲聚合后按句推送（见 doOnNext）
        }
        if (event instanceof ToolCallStartEvent e) {
            // 注：agent_spawn 的 Start 事件在门禁 onActing（能拿到入参）之前发射，
            // toolUseId→agent_id 注册来不及——显示工具名本身，前端可见「委派」动作发生
            return agentStatus(rootAgent, e.getSource(), e.getToolCallName(), "RUNNING");
        }
        if (event instanceof ToolResultEndEvent e) {
            String state = e.getState() != null ? e.getState().name() : "UNKNOWN";
            return agentStatus(rootAgent, e.getSource(), e.getToolCallName(), state);
        }
        if (event instanceof AgentEndEvent) {
            return null; // 结束事件在流完成回调中统一处理
        }
        return null;
    }

    /**
     * 构造 agent_status 事件（payload JSON：agent/action/state）
     * <p>
     * 2026-10-04 用户友好化：经 {@link ActivityPresenter} 把内部名映射为中文角色/动作，
     * 纯工程操作（缓存/文件/任务容器，白名单之外）返回 {@code null} 不推送——
     * 前端活动流只见中文动作，不见工程结构。
     * </p>
     *
     * @param rootAgent 根 Agent 名（runAgent=travel-master；PLANNING 编排=planning-agent）
     * @param source 事件源（null = 根 Agent；"conv-x/intake-agent" 形态解析出子代理名）
     */
    private ChatEvent agentStatus(String rootAgent, String source, String action, String state) {
        String agent = rootAgent;
        if (source != null && source.contains("/")) {
            agent = source.substring(source.lastIndexOf('/') + 1);
        }
        String actionLabel = ActivityPresenter.actionLabel(action);
        if (actionLabel == null) {
            return null; // 内部工具/未知名：白名单外不推送（mapNotNull 会丢弃）
        }
        JSONObject payload = new JSONObject();
        payload.put("agent", ActivityPresenter.agentLabel(agent));
        payload.put("action", actionLabel);
        payload.put("state", state);
        return ChatEvent.of(ChatEvent.TYPE_AGENT_STATUS, payload.toJSONString());
    }

    /**
     * PLANNING 轮 master 过程思考的聚合缓冲：流式 delta 分片很小（几个字），
     * 直接逐条发活动流会被切碎——攒到句读（。！？\n）或 80 字上限才作为一条 THINKING 推送。
     * thinkingFull 留全文副本：master 同轮直调工具后直接输出完整方案文本（未走 planner
     * 产文件路径）时，它是唯一的结果载体（handleComplete 兜底取用）。
     * <p>
     * 全部状态经参数传递（2026-09-23 串话修复）——本方法无实例字段依赖，并发轮次天然隔离。
     * </p>
     */
    private static void accumulateThinking(String rootAgent, String delta, StringBuilder thinkingBuf,
                                           StringBuilder thinkingFull,
                                           java.util.function.Consumer<String> thinkingEmitter) {
        if (delta == null || delta.isBlank()) {
            return;
        }
        thinkingBuf.append(delta);
        thinkingFull.append(delta);
        // 按句读切段推送
        int idx;
        while ((idx = endOfSentence(thinkingBuf)) >= 0) {
            String sentence = thinkingBuf.substring(0, idx).strip();
            thinkingBuf.delete(0, idx);
            if (!sentence.isEmpty()) {
                emitThinking(rootAgent, sentence, thinkingEmitter);
            }
        }
        // 超长无句读（模型连续输出无标点文本）：80 字硬切
        if (thinkingBuf.length() >= 80) {
            String chunk = thinkingBuf.toString().strip();
            thinkingBuf.setLength(0);
            emitThinking(rootAgent, chunk, thinkingEmitter);
        }
    }

    /** 找到第一个句读结束位置（含标点）；无则 -1 */
    private static int endOfSentence(StringBuilder buf) {
        for (int i = 0; i < buf.length(); i++) {
            char c = buf.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '\n' || c == ';' || c == '；') {
                return i + 1;
            }
        }
        return -1;
    }

    /** THINKING 事件发射（mapAgentEventStream 装配的 sink 通道，参数传递无共享状态） */
    private static void emitThinking(String rootAgent, String sentence,
                                     java.util.function.Consumer<String> thinkingEmitter) {
        // 消毒先行（2026-10-04）：含工程细节（工具名/任务ID/路径/系统指令词汇）的
        // 叙述句整句丢弃，再截断——先截断会把黑名单 token 切残导致漏检
        String clean = ActivityPresenter.sanitizeSentence(sentence);
        if (clean == null) {
            return;
        }
        if (clean.length() > 60) {
            clean = clean.substring(0, 60) + "…";
        }
        JSONObject payload = new JSONObject();
        payload.put("agent", ActivityPresenter.agentLabel(rootAgent));
        payload.put("action", clean);
        payload.put("state", "THINKING");
        thinkingEmitter.accept(payload.toJSONString());
    }

    /**
     * RAG 预留：知识片段拼入用户消息（知识库接入后生效）
     */
    private String augmentWithRag(String question, List<String> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return question;
        }
        String context = String.join("\n\n", fragments);
        return "请优先依据以下知识库检索结果回答；检索结果未覆盖的部分再结合你的知识，并标注「待确认」。\n\n"
                + "【知识库检索结果】\n" + context + "\n\n【用户问题】\n" + question;
    }

    private ChatEvent toIntentEvent(IntentResult intent) {
        return ChatEvent.of(ChatEvent.TYPE_INTENT, toIntentPayload(intent));
    }

    /** intent 事件的 JSON payload（LoadShed 拒绝路径也要先推意图，前端才渲染标签） */
    private String toIntentPayload(IntentResult intent) {
        JSONObject payload = new JSONObject();
        payload.put("intent", intent != null && intent.intent != null ? intent.intent : "UNKNOWN");
        payload.put("reason", intent != null && intent.reason != null ? intent.reason : "");
        return payload.toJSONString();
    }

    private void sendEvent(SseEmitter emitter, ChatEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.getType()).data(
                    event.getPayload() != null ? event.getPayload() : ""));
        } catch (IOException | IllegalStateException e) {
            log.warn("SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * Agent 层异常 → SSE error 降级文案（FR-S09 兜底：熔断 OPEN / fallback 也失败时给用户明确提示，不白屏）
     */
    private String sseFallbackMessage(Throwable e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (msg.contains("CircuitBreaker") || msg.contains("CallNotPermitted")) {
            return "模型服务暂时不可用（已触发熔断保护），请稍等片刻再试；我们已自动降级重试。";
        }
        if (msg.contains("401") || msg.contains("Unauthorized") || msg.contains("invalid")) {
            return "模型服务认证异常，请稍后再试或联系管理员。";
        }
        // 账户欠费（DashScope Arrearage）：原始报文含 request_id/错误码等工程细节，不透传
        if (msg.contains("Arrearage") || msg.contains("good standing")) {
            return "AI 服务账户余额不足，请联系管理员充值后重试（模型服务已拒绝请求）。";
        }
        return "处理失败，请稍后重试（" + msg + "）";
    }

    private void handleError(SseEmitter emitter, StringBuilder replyBuf, Conversation conversation,
                             Long userId, Throwable error, TurnUsageTracker usageTracker, com.travelscope.assessment.ObservationContext observation) {
        log.error("对话处理异常: conversationId={}", conversation.getId(), error);
        llmGateway.release(String.valueOf(userId));
        persistReply(conversation, userId, replyBuf, usageTracker);
        // 部分回复也已落库计入消息总数，同样检查摘要是否到期
        conversationSummaryService.maybeSummarizeAsync(conversation.getId(), userId, observation);
        try {
            emitter.send(SseEmitter.event().name(ChatEvent.TYPE_ERROR)
                    .data(sseFallbackMessage(error)));
        } catch (Exception ignored) {
            // 客户端已断开
        }
        emitter.complete();
    }

    private void handleComplete(SseEmitter emitter, StringBuilder replyBuf, Conversation conversation,
                                Long userId, StringBuilder thinkingFull, TurnUsageTracker usageTracker, com.travelscope.assessment.ObservationContext observation) {
        llmGateway.release(String.valueOf(userId));
        // 过程/结果分离（2026-09-22）：PLANNING 轮 replyBuf 为空（过程文本未进缓冲），
        // 最终方案从协作目录整合生成——itinerary_draft.md 优先，缺则 execution_result.md；
        // 两者都无（规划中途结束/仅反问轮）保持原样（可能为空，前端显示活动流即可）
        String reply = replyBuf.toString();
        if (reply.isBlank()) {
            String assembled = assemblePlanningReply(String.valueOf(userId),
                    SESSION_PREFIX + conversation.getId());
            if (assembled == null) {
                // 兜底：master 同轮直调工具后直接输出了完整方案文本（未走 planner 产
                // itinerary_draft 的路径）——THINKING 全文副本就是方案本体（>300 字才算方案，
                // 少于则是纯过程叙述如反问轮）
                String full = thinkingFull.toString().strip();
                if (full.length() > 300) {
                    assembled = full;
                    log.info("PLANNING 最终方案取自 master 当轮输出（无协作产物文件）: {} 字符", full.length());
                }
            }
            if (assembled != null) {
                reply = assembled;
                replyBuf.setLength(0);
                replyBuf.append(reply);
            }
        }
        persistReply(conversation, userId, replyBuf, usageTracker);
        // 记忆摘要到期检查（每满 10 轮后台折叠一次，fire-and-forget 不阻塞 done 事件）
        conversationSummaryService.maybeSummarizeAsync(conversation.getId(), userId, observation);
        // FR-S08：质检报告事件——done 前把本轮评审结论推给前端（S3.1 起数据源切 JSON 产物）：
        // review_passed.json 优先（passed=true），回炉超限场景退化为最近一次 review_report.json
        String reviewPayload = readReviewPayload(String.valueOf(userId),
                SESSION_PREFIX + conversation.getId());
        if (reviewPayload != null) {
            try {
                emitter.send(SseEmitter.event().name(ChatEvent.TYPE_REVIEW_REPORT)
                        .data(reviewPayload));
            } catch (Exception ignored) {
                // 报告事件失败不影响 done
            }
        }
        try {
            emitter.send(SseEmitter.event().name(ChatEvent.TYPE_DONE)
                    .data(replyBuf.toString()));
        } catch (Exception ignored) {
            // 客户端已断开
        }
        emitter.complete();
    }

    /**
     * PLANNING 轮最终方案整合（过程/结果分离改造的数据源）：
     * itinerary_draft.md 优先（planner 产出的完整方案）；缺失时退化 execution_result.md；
     * 都无返回 null（本轮没走到方案产出，如反问轮/中途失败——正文为空，活动流已展示过程）。
     */
    private String assemblePlanningReply(String userId, String agentSessionId) {
        try {
            // S2.6：JSON 契约优先——planner 经 write_collab_file 产 itinerary_draft.json
            //（markdownBody 即方案正文）；缺失时退回 v3 的 .md 产物（过渡期兼容）
            java.util.Optional<com.travelscope.collab.ItineraryDraft> draftJson =
                    taskWorkspaceService.readItineraryDraft(userId, agentSessionId);
            if (draftJson.isPresent() && draftJson.get().markdownBody() != null
                    && !draftJson.get().markdownBody().isBlank()) {
                log.info("PLANNING 最终方案取自 itinerary_draft.json: 会话={}, {} 字符",
                        agentSessionId, draftJson.get().markdownBody().length());
                return draftJson.get().markdownBody();
            }
            java.nio.file.Path dir = taskWorkspaceService.getTaskDir(userId, agentSessionId);
            // 过渡（S0.5）：planner 产物 JSON 化前的 v3 存量 .md 兜底
            java.nio.file.Path draft = dir.resolve(TaskWorkspace.FILE_ITINERARY_DRAFT_MD);
            if (java.nio.file.Files.exists(draft)) {
                String content = java.nio.file.Files.readString(draft, java.nio.charset.StandardCharsets.UTF_8);
                if (!content.isBlank()) {
                    log.info("PLANNING 最终方案取自 itinerary_draft.md: 会话={}, {} 字符", agentSessionId, content.length());
                    return content;
                }
            }
            java.nio.file.Path execution = dir.resolve(TaskWorkspace.FILE_EXECUTION_RESULT_MD);
            if (java.nio.file.Files.exists(execution)) {
                String content = java.nio.file.Files.readString(execution, java.nio.charset.StandardCharsets.UTF_8);
                if (!content.isBlank()) {
                    log.info("PLANNING 最终方案取自 execution_result.md: 会话={}, {} 字符", agentSessionId, content.length());
                    return content;
                }
            }
        } catch (Exception e) {
            log.warn("读取协作目录整合最终方案失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 读取本轮质检结论并组装 review_report 事件载荷（JSON 字符串）。
     * <p>
     * S3.1 起数据源为 JSON 产物（reviewer 经 write_collab_file 写入）：review_passed.json
     * 优先（passed=true，无分段明细，segments 为空）；缺失时退化 review_report.json
     * （passed=false，携带各分段 pass 标志）。前端契约：passed/total 语义不变，segments 新增。
     * </p>
     * 两产物都不存在（未走到质检/产物异常）返回 null 不发事件，log.warn 留痕。
     */
    private String readReviewPayload(String userId, String agentSessionId) {
        java.util.Optional<com.travelscope.collab.ReviewPassed> passed =
                taskWorkspaceService.readReviewPassed(userId, agentSessionId);
        if (passed.isPresent()) {
            JSONObject payload = new JSONObject();
            payload.put("passed", true);
            payload.put("total", passed.get().total());
            payload.put("segments", java.util.List.of());
            return payload.toJSONString();
        }
        java.util.Optional<com.travelscope.collab.ReviewReport> report =
                taskWorkspaceService.readReviewReport(userId, agentSessionId);
        if (report.isPresent()) {
            JSONObject payload = new JSONObject();
            payload.put("passed", false);
            payload.put("total", report.get().total());
            payload.put("segments", report.get().segments().stream()
                    .map(s -> {
                        JSONObject seg = new JSONObject();
                        seg.put("segment", s.segment());
                        seg.put("pass", s.pass());
                        return seg;
                    })
                    .toList());
            return payload.toJSONString();
        }
        log.warn("review 产物缺失（review_passed.json / review_report.json 均不存在），"
                + "不推 review_report 事件: userId={}, session={}", userId, agentSessionId);
        return null;
    }

    private void persistReply(Conversation conversation, Long userId, StringBuilder replyBuf,
                              TurnUsageTracker usageTracker) {
        String reply = replyBuf.toString();
        if (reply.isBlank()) {
            return;
        }
        try {
            // P1-K 真实口径：有 usage 则记真实 token + 当轮实际生效模型名（fallback 轮记 fallback）；
            // 无 usage（网关未回）传 null → ConversationService 回落估算
            Integer realTokens = usageTracker.hasRealUsage()
                    ? (int) usageTracker.totalTokens() : null;
            String actualModel = usageTracker.hasRealUsage()
                    ? gatewayChatModel.getModelName() : null;
            conversationService.saveAssistantMessage(conversation, userId, reply, realTokens, actualModel);
            if (realTokens == null) {
                log.debug("本轮无真实 usage（网关未回），回落估算口径: conversationId={}",
                        conversation.getId());
            }
        } catch (Exception e) {
            log.error("保存助手回复失败: conversationId={}", conversation.getId(), e);
        }
    }
}
