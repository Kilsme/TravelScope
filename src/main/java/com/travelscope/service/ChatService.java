package com.travelscope.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.travelscope.agent.IntentCascadeRouter;
import com.travelscope.agent.IntentRouterMiddleware;
import com.travelscope.agent.IntentType;
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
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.UserMessage;
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

import static com.travelscope.config.AgentConfig.TaskWorkspaceService;

/**
 * 对话编排服务
 * <p>
 * 核心流程（应用层意图路由，参考 AgentScope 官方 Application-Layer 编排模式）：
 * <pre>
 * 用户消息 → 意图分类 Agent（结构化输出）
 *   ├─ CHAT / TOOL_CALL / RAG → 主 Agent + 路由指令（IntentRouterMiddleware 禁止委派）
 *   ├─ RAG（知识库可用时）    → 先检索知识片段拼入上下文再回答
 *   └─ PLANNING              → 主 Agent 拆分任务并委派 planning-agent
 * </pre>
 * 事件流通过 SseEmitter 推送给前端；意图分类（阻塞调用）与 Agent 执行均调度到
 * boundedElastic 线程池，不占用容器线程。
 * </p>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** AgentScope 会话 ID 前缀（与 conversations 表 ID 关联，保证多轮上下文） */
    private static final String SESSION_PREFIX = "conv-";

    private final HarnessAgent travelMasterAgent;
    private final IntentCascadeRouter intentCascadeRouter;
    private final RagService ragService;
    private final ConversationService conversationService;
    private final TaskWorkspaceService taskWorkspaceService;
    private final TripRequirementStore tripRequirementStore;
    private final LlmGateway llmGateway;
    /** RAG 检索 topK（travelscope.rag.top-k，原硬编码 5） */
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

    public ChatService(HarnessAgent travelMasterAgent,
                       IntentCascadeRouter intentCascadeRouter,
                       RagService ragService,
                       ConversationService conversationService,
                       TaskWorkspaceService taskWorkspaceService,
                       TripRequirementStore tripRequirementStore,
                       LlmGateway llmGateway,
                       LoadShedService loadShedService,
                       ConversationSummaryService conversationSummaryService,
                       TracingHelper tracing,
                       com.travelscope.config.AppProperties appProperties) {
        this.travelMasterAgent = travelMasterAgent;
        this.intentCascadeRouter = intentCascadeRouter;
        this.ragService = ragService;
        this.conversationService = conversationService;
        this.taskWorkspaceService = taskWorkspaceService;
        this.tripRequirementStore = tripRequirementStore;
        this.llmGateway = llmGateway;
        this.ragTopK = appProperties.getRag().getTopK();
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

        // LLM Gateway 准入（FR-S09）：全局/单用户并发 + QPS；被拒 → SSE error 降级文案（不白屏）
        LlmGateway.AcquireResult admission = llmGateway.tryAcquire(String.valueOf(userId));
        if (!admission.allowed()) {
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
        String turnSessionKey = SESSION_PREFIX + conversation.getId();
        TracingHelper.SpanHandle turnSpan = tracing.startSpan("chat-turn", Map.of(
                "userId", String.valueOf(userId),
                "sessionId", turnSessionKey));
        tracing.beginTurn(String.valueOf(userId), turnSessionKey, turnSpan);

        Disposable disposable;
        try (var spanScope = turnSpan.inScope()) {
            disposable = Mono
                    .fromCallable(() -> resolveIntent(conversation, userId, userMessage))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(intent -> {
                        // 意图分类完成，补标 chat-turn 的 intent 属性（含 UNKNOWN 兜底）
                        turnSpan.tag("intent", intent != null && intent.toIntentType() != null
                                ? intent.toIntentType().name() : "UNKNOWN");
                        // 过载降级准入（2026-09-23）：意图明确后判定——YELLOW 只挡新 PLANNING
                        // （慢泳道内存大头），快请求继续服务；RED 挡全部新对话。
                        // 注意：此时网关槽位已持有，拒绝路径须 release
                        boolean planningIntent = intent != null && intent.toIntentType() == IntentType.PLANNING;
                        String shed = loadShedService.tryAdmit(planningIntent);
                        if (shed != null) {
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
                        return Flux.concat(
                            Flux.just(toIntentEvent(intent)),
                            runAgent(conversation, userId, userMessage, intent, replyBuf, thinkingFull))
                            // 快慢泳道线程池隔离（FR-S09）：按意图把整条对话链（含 Agent 执行）
                            // 调度到各自泳道池——PLANNING 的同步 spawn 长阻塞只占慢池，
                            // 查询类在快池独立执行互不拖拽（最内层 subscribeOn 生效于 Agent 链源头）
                            .subscribeOn(intent != null && intent.toIntentType() == IntentType.PLANNING
                                    ? slowLaneScheduler : fastLaneScheduler);
                    })
                    // 终态（含取消/超时 dispose）结束 chat-turn span；会话父注册跨轮存续
                    // （TracingHelper.beginTurn 注释：异步 spawn 的后台子链晚到的打点仍挂本轮 trace）
                    .doFinally(signal -> turnSpan.end())
                    .subscribe(
                            event -> sendEvent(emitter, event),
                            error -> handleError(emitter, replyBuf, conversation, userId, error),
                            () -> handleComplete(emitter, replyBuf, conversation, userId, thinkingFull));
        }

        emitter.onTimeout(disposable::dispose);
        emitter.onError(t -> disposable.dispose());
        emitter.onCompletion(disposable::dispose);
        return emitter;
    }

    /**
     * 意图分类（阻塞调用，运行在 boundedElastic；FR-S01 三层级联入口）
     * <p>
     * L0 会话延续 → L1 规则表 → L2 qwen-turbo → L3 IntentClassifier 兜底，
     * 上层命中即短路（见 IntentCascadeRouter）；日志格式 cascade_hit=L{n} latency=xms。
     *
     * @return 分类结果；失败返回 null（主 Agent 按自身提示词自主路由，不注入指令）
     */
    private IntentResult resolveIntent(Conversation conversation, Long userId, String userMessage) {
        // 意图分类上下文注入（失忆修复 Fix 4）：messages 表 + 记忆摘要构建上下文块随消息
        // 送 L2/L3——「长春」这类对 intake 反问的裸名词短回答在语境下判 PLANNING，不再
        // 被判 CHAT 脱离规划流。messages 表是唯一可靠事实源（L0/状态机/缓存都是加速层，
        // 丢失可由此兜回）；效果依赖 POST /chat/stream 回传正确 conversationId。
        String contextBlock = "";
        if (intentContextEnabled) {
            List<Message> messages = conversationService.listMessages(conversation.getId());
            String summary = appProperties.getChatHistory().getSummary().isEnabled()
                    ? conversation.getSummary() : null;
            contextBlock = intentContextBuilder.build(messages, summary);
        }
        IntentResult result = intentCascadeRouter.classify(
                userMessage, contextBlock, String.valueOf(userId),
                SESSION_PREFIX + conversation.getId());
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
                                     IntentResult intent, StringBuilder replyBuf, StringBuilder thinkingFull) {
        RuntimeContext ctx = RuntimeContext.builder()
                .userId(String.valueOf(userId))
                .sessionId(SESSION_PREFIX + conversation.getId())
                .build();
        IntentType type = intent != null ? intent.toIntentType() : null;
        if (type != null) {
            ctx.put(IntentRouterMiddleware.CTX_INTENT_KEY, type.name());
        }
        // 需求 1：把本会话的隔离协作目录（相对路径 tasks/{sessionId}）注入上下文，
        // 供 IntentRouterMiddleware 在 PLANNING 指令中给出具体路径，并供委派门禁按会话校验
        String agentSessionId = SESSION_PREFIX + conversation.getId();
        ctx.put(IntentRouterMiddleware.CTX_COLLAB_DIR_KEY,
                taskWorkspaceService.collabDirRelativePath(agentSessionId));
        // 前端可见性改造（2026-10-04）：PLANNING 轮强制 spawn 同步等待 420s——框架默认
        // 同步等待仅 30s，规划全链（含分段回炉）实测 275~320s，必然「超时转后台」，
        // master 只能回「后台运行中」并结束回合，前端从此无反馈、结果要等下一条消息。
        // 双键经 AgentSpawnTool.resolveEffectiveTimeoutMs 生效（仅 force_sync 时读 ctx 键，
        // 上限 clamp 600s）；超时行为从「转后台」变为「中断并明确报超时」。
        if (type == IntentType.PLANNING) {
            ctx.put(AgentSpawnTool.CTX_FORCE_SYNC, Boolean.TRUE);
            ctx.put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 420);
        }

        String outgoing = userMessage;
        if (type == IntentType.RAG && ragService.isAvailable()) {
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
        if (historyEnabled) {
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
            conversationSummaryService.maybeSummarizeAsync(conversation.getId(), userId);
        }

        UserMessage msg = new UserMessage(outgoing);
        // 快慢泳道（FR-S09）：PLANNING → 慢泳道 300s；其余（CHAT/TOOL_CALL/RAG/null）→ 快泳道。
        // 查询类不被进行中的长规划拖慢（独立链路 + 各自超时保护），超时 → SSE error 兜底文案
        Duration laneTimeout = llmGateway.laneTimeout(type == IntentType.PLANNING);
        boolean planning = type == IntentType.PLANNING;
        // THINKING 聚合句发射通道（accumulateThinking → Sinks.Many → mergeWith 回 SSE 流）：
        // 主流 doFinally 时 emitComplete——mergeWith 等两条流都完成才结束，Flux.create+轮询线程
        // 会永不 complete 导致 done 发不出（E2E 实测）；Sinks 无需独立线程且即时推送。
        // 全部为方法局部变量（2026-09-23 串话修复：原实例字段在并发 PLANNING 轮互相覆盖）
        reactor.core.publisher.Sinks.Many<ChatEvent> thinkingSink =
                reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();
        StringBuilder thinkingBuf = new StringBuilder();
        java.util.function.Consumer<String> thinkingEmitter = payload -> thinkingSink.tryEmitNext(
                ChatEvent.of(ChatEvent.TYPE_AGENT_STATUS, payload));
        return travelMasterAgent.streamEvents(msg, ctx)
                // 主 Agent 自身事件 + intake 反问 + 子 Agent 工具活动（前端可见性改造：
                // master 同步等待期间，子 Agent 的工具 Start/End 转为 agent_status 推送；
                // 子 Agent 文本 delta 仍被 isMainAgentEvent 拦截——思维链不外泄）
                .filter(event -> isMainAgentEvent(event) || isClarifyEvent(event)
                        || isSubAgentToolEvent(event))
                .doOnNext(event -> {
                    // 过程/结果分离（2026-09-22）：PLANNING 轮 master 的过程文本不进 replyBuf——
                    // replyBuf 只保留给最终方案（done/入库），过程叙述按句聚合后进活动流
                    if (event instanceof TextBlockDeltaEvent e) {
                        if (!planning) {
                            replyBuf.append(e.getDelta());
                        } else {
                            accumulateThinking(e.getDelta(), thinkingBuf, thinkingFull, thinkingEmitter);
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
                .mapNotNull(event -> toChatEvent(event, planning))
                .timeout(laneTimeout)
                .doFinally(signal -> {
                    // 残留缓冲 flush + 关闭 THINKING 通道（mergeWith 需两流都 complete）
                    if (planning && !thinkingBuf.isEmpty()) {
                        String rest = thinkingBuf.toString().strip();
                        thinkingBuf.setLength(0);
                        if (!rest.isEmpty()) {
                            emitThinking(rest, thinkingEmitter);
                        }
                    }
                    thinkingSink.tryEmitComplete();
                })
                .onErrorResume(e -> {
                    if (e instanceof java.util.concurrent.TimeoutException) {
                        log.warn("泳道超时: 意图={}, 泳道={}s, conversationId={}",
                                type, laneTimeout.toSeconds(), conversation.getId());
                        return Flux.just(ChatEvent.of(ChatEvent.TYPE_ERROR,
                                "处理超时了（" + laneTimeout.toSeconds() + " 秒上限），请稍后重试或换个问法～"));
                    }
                    log.error("Agent 执行异常: conversationId={}", conversation.getId(), e);
                    return Flux.just(ChatEvent.of(ChatEvent.TYPE_ERROR,
                            sseFallbackMessage(e)));
                })
                // THINKING 聚合句与主事件流合并（主流 complete 时 sink 同步关闭）
                .mergeWith(thinkingSink.asFlux());
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
    private ChatEvent toChatEvent(AgentEvent event, boolean planning) {
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
            return agentStatus(e.getSource(), e.getToolCallName(), "RUNNING");
        }
        if (event instanceof ToolResultEndEvent e) {
            String state = e.getState() != null ? e.getState().name() : "UNKNOWN";
            return agentStatus(e.getSource(), e.getToolCallName(), state);
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
     * @param source 事件源（null = 主 Agent；"conv-x/intake-agent" 形态解析出子代理名）
     */
    private ChatEvent agentStatus(String source, String action, String state) {
        String agent = "travel-master";
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
    private static void accumulateThinking(String delta, StringBuilder thinkingBuf,
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
                emitThinking(sentence, thinkingEmitter);
            }
        }
        // 超长无句读（模型连续输出无标点文本）：80 字硬切
        if (thinkingBuf.length() >= 80) {
            String chunk = thinkingBuf.toString().strip();
            thinkingBuf.setLength(0);
            emitThinking(chunk, thinkingEmitter);
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

    /** THINKING 事件发射（runAgent 装配的 sink 通道，参数传递无共享状态） */
    private static void emitThinking(String sentence, java.util.function.Consumer<String> thinkingEmitter) {
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
        payload.put("agent", ActivityPresenter.agentLabel("travel-master"));
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
                             Long userId, Throwable error) {
        log.error("对话处理异常: conversationId={}", conversation.getId(), error);
        llmGateway.release(String.valueOf(userId));
        persistReply(conversation, userId, replyBuf);
        // 部分回复也已落库计入消息总数，同样检查摘要是否到期
        conversationSummaryService.maybeSummarizeAsync(conversation.getId(), userId);
        try {
            emitter.send(SseEmitter.event().name(ChatEvent.TYPE_ERROR)
                    .data(sseFallbackMessage(error)));
        } catch (Exception ignored) {
            // 客户端已断开
        }
        emitter.complete();
    }

    private void handleComplete(SseEmitter emitter, StringBuilder replyBuf, Conversation conversation,
                                Long userId, StringBuilder thinkingFull) {
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
        persistReply(conversation, userId, replyBuf);
        // 记忆摘要到期检查（每满 10 轮后台折叠一次，fire-and-forget 不阻塞 done 事件）
        conversationSummaryService.maybeSummarizeAsync(conversation.getId(), userId);
        // FR-S08：质检报告事件——本轮回复含质检标注时，读协作目录的 review 文件推给前端
        // （review_passed.md 优先，回炉超限场景退化为最近一次 review_report.md）
        try {
            String reviewMarkdown = readReviewMarkdown(String.valueOf(userId),
                    SESSION_PREFIX + conversation.getId(), replyBuf.toString());
            if (reviewMarkdown != null) {
                emitter.send(SseEmitter.event().name(ChatEvent.TYPE_REVIEW_REPORT)
                        .data(reviewMarkdown));
            }
        } catch (Exception ignored) {
            // 报告事件失败不影响 done
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
            java.nio.file.Path dir = taskWorkspaceService.getTaskDir(userId, agentSessionId);
            java.nio.file.Path draft = dir.resolve(TaskWorkspaceService.FILE_ITINERARY_DRAFT);
            if (java.nio.file.Files.exists(draft)) {
                String content = java.nio.file.Files.readString(draft, java.nio.charset.StandardCharsets.UTF_8);
                if (!content.isBlank()) {
                    log.info("PLANNING 最终方案取自 itinerary_draft.md: 会话={}, {} 字符", agentSessionId, content.length());
                    return content;
                }
            }
            java.nio.file.Path execution = dir.resolve(TaskWorkspaceService.FILE_EXECUTION_RESULT);
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
     * 读取本轮质检报告 markdown（检测标准 2 的数据源）：
     * 回复含「已通过质量审阅」→ review_passed.md；含「已尽力」→ 最近一次 review_report.md。
     * 回复无质检标注（纯查询/闲聊/未走到质检）返回 null 不推事件。
     */
    private String readReviewMarkdown(String userId, String agentSessionId, String reply) {
        boolean passed = reply.contains("已通过质量审阅");
        boolean bestEffort = reply.contains("已尽力");
        if (!passed && !bestEffort) {
            return null;
        }
        try {
            java.nio.file.Path dir = taskWorkspaceService.getTaskDir(userId, agentSessionId);
            java.nio.file.Path target = passed
                    ? dir.resolve(TaskWorkspaceService.FILE_REVIEW_PASSED)
                    : dir.resolve(TaskWorkspaceService.FILE_REVIEW_REPORT);
            if (java.nio.file.Files.exists(target)) {
                return java.nio.file.Files.readString(target, java.nio.charset.StandardCharsets.UTF_8);
            }
            // 通过标注但 passed 文件缺失（防御）：退化读 report
            java.nio.file.Path fallback = dir.resolve(TaskWorkspaceService.FILE_REVIEW_REPORT);
            if (passed && java.nio.file.Files.exists(fallback)) {
                return java.nio.file.Files.readString(fallback, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("读取质检报告失败（不推 review_report 事件）: {}", e.getMessage());
        }
        return null;
    }

    private void persistReply(Conversation conversation, Long userId, StringBuilder replyBuf) {
        String reply = replyBuf.toString();
        if (reply.isBlank()) {
            return;
        }
        try {
            conversationService.saveAssistantMessage(conversation, userId, reply);
        } catch (Exception e) {
            log.error("保存助手回复失败: conversationId={}", conversation.getId(), e);
        }
    }
}
