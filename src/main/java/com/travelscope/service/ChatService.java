package com.travelscope.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.travelscope.agent.IntentCascadeRouter;
import com.travelscope.agent.IntentRouterMiddleware;
import com.travelscope.agent.IntentType;
import com.travelscope.dto.ChatEvent;
import com.travelscope.dto.IntentResult;
import com.travelscope.entity.Conversation;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.List;

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

    public ChatService(HarnessAgent travelMasterAgent,
                       IntentCascadeRouter intentCascadeRouter,
                       RagService ragService,
                       ConversationService conversationService,
                       TaskWorkspaceService taskWorkspaceService) {
        this.travelMasterAgent = travelMasterAgent;
        this.intentCascadeRouter = intentCascadeRouter;
        this.ragService = ragService;
        this.conversationService = conversationService;
        this.taskWorkspaceService = taskWorkspaceService;
    }

    /**
     * 处理一轮对话：持久化用户消息 → 意图分类 → 路由执行 → SSE 推送 → 持久化回复
     *
     * @param conversation 会话（已校验归属）
     * @param userId       用户 ID
     * @param userMessage  用户消息原文
     * @return SSE 发射器
     */
    public SseEmitter streamChat(Conversation conversation, Long userId, String userMessage) {
        conversationService.saveUserMessage(conversation, userId, userMessage);

        SseEmitter emitter = new SseEmitter(0L);
        StringBuilder replyBuf = new StringBuilder();

        Disposable disposable = Mono
                .fromCallable(() -> resolveIntent(userMessage, userId, conversation.getId()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(intent -> Flux.concat(
                        Flux.just(toIntentEvent(intent)),
                        runAgent(conversation, userId, userMessage, intent, replyBuf)))
                .subscribe(
                        event -> sendEvent(emitter, event),
                        error -> handleError(emitter, replyBuf, conversation, userId, error),
                        () -> handleComplete(emitter, replyBuf, conversation, userId));

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
    private IntentResult resolveIntent(String userMessage, Long userId, Long conversationId) {
        IntentResult result = intentCascadeRouter.classify(
                userMessage, String.valueOf(userId), SESSION_PREFIX + conversationId);
        if (result != null && result.toIntentType() == IntentType.RAG) {
            log.info("意图=RAG，知识库可用: {}（不可用时由路由指令回退为直接回答）", ragService.isAvailable());
        }
        return result;
    }

    /**
     * 运行主 Agent 并把事件流映射为 SSE 事件
     */
    private Flux<ChatEvent> runAgent(Conversation conversation, Long userId, String userMessage,
                                     IntentResult intent, StringBuilder replyBuf) {
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

        String outgoing = userMessage;
        if (type == IntentType.RAG && ragService.isAvailable()) {
            outgoing = augmentWithRag(userMessage, ragService.retrieve(userMessage, 5));
        }

        UserMessage msg = new UserMessage(outgoing);
        return travelMasterAgent.streamEvents(msg, ctx)
                .filter(this::isMainAgentEvent)
                .doOnNext(event -> {
                    if (event instanceof TextBlockDeltaEvent e) {
                        replyBuf.append(e.getDelta());
                    }
                })
                .mapNotNull(this::toChatEvent)
                .onErrorResume(e -> {
                    log.error("Agent 执行异常: conversationId={}", conversation.getId(), e);
                    return Flux.just(ChatEvent.of(ChatEvent.TYPE_ERROR, "Agent 执行异常: " + e.getMessage()));
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
     * AgentEvent → SSE 事件映射（非推送事件返回 null 被过滤）
     */
    private ChatEvent toChatEvent(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent e) {
            return ChatEvent.of(ChatEvent.TYPE_DELTA, e.getDelta());
        }
        if (event instanceof ToolCallStartEvent e) {
            return ChatEvent.of(ChatEvent.TYPE_TOOL, e.getToolCallName());
        }
        if (event instanceof ToolResultEndEvent e) {
            return ChatEvent.of(ChatEvent.TYPE_TOOL,
                    e.getToolCallName() + ":" + (e.getState() != null ? e.getState().name() : "UNKNOWN"));
        }
        if (event instanceof AgentEndEvent) {
            return null; // 结束事件在流完成回调中统一处理
        }
        return null;
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
        JSONObject payload = new JSONObject();
        payload.put("intent", intent != null && intent.intent != null ? intent.intent : "UNKNOWN");
        payload.put("reason", intent != null && intent.reason != null ? intent.reason : "");
        return ChatEvent.of(ChatEvent.TYPE_INTENT, payload.toJSONString());
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

    private void handleError(SseEmitter emitter, StringBuilder replyBuf, Conversation conversation,
                             Long userId, Throwable error) {
        log.error("对话处理异常: conversationId={}", conversation.getId(), error);
        persistReply(conversation, userId, replyBuf);
        try {
            emitter.send(SseEmitter.event().name(ChatEvent.TYPE_ERROR)
                    .data("处理失败: " + error.getMessage()));
        } catch (Exception ignored) {
            // 客户端已断开
        }
        emitter.complete();
    }

    private void handleComplete(SseEmitter emitter, StringBuilder replyBuf, Conversation conversation, Long userId) {
        persistReply(conversation, userId, replyBuf);
        try {
            emitter.send(SseEmitter.event().name(ChatEvent.TYPE_DONE)
                    .data(replyBuf.toString()));
        } catch (Exception ignored) {
            // 客户端已断开
        }
        emitter.complete();
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
