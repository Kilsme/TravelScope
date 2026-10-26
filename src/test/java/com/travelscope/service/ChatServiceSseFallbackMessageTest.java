package com.travelscope.service;

import com.travelscope.agent.IntentCascadeRouter;
import com.travelscope.collab.TaskWorkspace;
import com.travelscope.common.TracingHelper;
import com.travelscope.config.AppProperties;
import com.travelscope.llm.AllProvidersOpenException;
import com.travelscope.service.workflow.RequirementWorkflowService;
import com.travelscope.service.workflow.TaskBacklogPlanner;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v5-F3 全灭兜底链路测试（2026-10-22）：
 * <ol>
 *   <li>意图分类失败（返回 null，如模型池全灭）→ 不再静默空 done——intent(UNKNOWN) 事件
 *       照发、主 Agent 链照走（Optional 包装修复：fromCallable(null) 空 Mono 会整体跳过
 *       flatMapMany，用户只收到空 done 白屏）；</li>
 *   <li>主 Agent 模型错误（AllProvidersOpenException）→ mapAgentEventStream 通用分支转
 *       SSE error「AI 服务暂时不可用，请联系管理员。」；</li>
 *   <li>sseFallbackMessage 对全灭异常（裸/被包装）的文案映射。</li>
 * </ol>
 */
class ChatServiceSseFallbackMessageTest {

    private ChatService newService(HarnessAgent masterAgent, IntentCascadeRouter router) {
        ChatService service = new ChatService(masterAgent, router,
                mock(RagService.class), mock(ConversationService.class), mock(TaskWorkspace.class),
                mock(TripRequirementStore.class), mock(LlmGateway.class), mock(LoadShedService.class),
                mock(ConversationSummaryService.class), TracingHelper.NOOP, new AppProperties(),
                mock(Model.class), mock(PointsService.class), mock(RequirementWorkflowService.class),
                mock(TaskBacklogPlanner.class), mock(HarnessAgent.class),
                mock(io.agentscope.core.model.Model.class));
        return service;
    }

    private static void stubAdmission(ChatService service) throws Exception {
        var llmGateway = (LlmGateway) field(service, "llmGateway");
        when(llmGateway.tryAcquire(anyString())).thenReturn(LlmGateway.AcquireResult.ok());
        when(llmGateway.laneTimeout(anyBoolean())).thenReturn(Duration.ofSeconds(25));
        var points = (PointsService) field(service, "pointsService");
        when(points.costOf(anyBoolean())).thenReturn(10);
        when(points.tryDeduct(any(), anyInt())).thenReturn(true);
        var loadShed = (LoadShedService) field(service, "loadShedService");
        when(loadShed.tryAdmit(anyBoolean())).thenReturn(null);
        var conversations = (ConversationService) field(service, "conversationService");
        when(conversations.listMessages(any())).thenReturn(List.of());
    }

    private static Object field(ChatService service, String name) throws Exception {
        Field f = ChatService.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(service);
    }

    /** 读取 SSE 已发全部条目（无 MVC handler 时 send 缓冲在 earlySendAttempts 字段） */
    @SuppressWarnings("unchecked")
    private static List<String> sentItems(SseEmitter emitter) throws Exception {
        Field field = ResponseBodyEmitter.class.getDeclaredField("earlySendAttempts");
        field.setAccessible(true);
        synchronized (emitter) {
            Set<ResponseBodyEmitter.DataWithMediaType> attempts =
                    (Set<ResponseBodyEmitter.DataWithMediaType>) field.get(emitter);
            return attempts.stream().map(a -> String.valueOf(a.getData())).toList();
        }
    }

    private static List<String> awaitEvent(SseEmitter emitter, String eventName) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            List<String> items = sentItems(emitter);
            // 条目形如 "event:{name}\ndata:..."——取首行判名（与编排测试 eventNames 同口径）
            if (items.stream().map(String::trim)
                    .anyMatch(s -> s.equals("event:" + eventName)
                            || s.startsWith("event:" + eventName + "\n"))) {
                return items;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待 SSE 事件超时: " + eventName + "，已收到: " + sentItems(emitter));
    }

    @Test
    @DisplayName("分类失败(null) → intent(UNKNOWN)+主 Agent 链照走，模型错误转全灭 SSE 文案（不再静默空 done）")
    void classificationFailureStillRunsAgentAndMapsAllOpenToSseError() throws Exception {
        HarnessAgent master = mock(HarnessAgent.class);
        // 主 Agent 模型链抛全灭异常（mapAgentEventStream 通用分支 → SSE error）
        when(master.streamEvents(any(UserMessage.class), any(RuntimeContext.class)))
                .thenReturn(Flux.error(new AllProvidersOpenException("main 全部 provider 熔断（tried=[glm, kimi]）")));
        IntentCascadeRouter router = mock(IntentCascadeRouter.class);
        when(router.classify(anyString(), any(), anyString(), anyString())).thenReturn(null);
        ChatService service = newService(master, router);
        stubAdmission(service);

        com.travelscope.entity.Conversation conversation = new com.travelscope.entity.Conversation();
        conversation.setId(901L);
        conversation.setUserId(1L);
        SseEmitter emitter = ChatExecutionTestSupport.stream(service, conversation, 1L, "帮我看看天气");

        List<String> items = awaitEvent(emitter, "error");
        String flattened = String.join("\n", items);
        assertTrue(flattened.contains("event:intent"),
                "分类失败也应发 intent 事件（UNKNOWN 兜底），实际: " + items);
        assertTrue(flattened.contains("UNKNOWN"),
                "intent 载荷应为 UNKNOWN 兜底，实际: " + items);
        assertTrue(flattened.contains("AI 服务暂时不可用，请联系管理员"),
                "主 Agent 模型错误应转全灭 SSE 文案，实际: " + items);
        assertTrue(!flattened.contains("event:done"), "失败不可再按成功收尾");
    }

    @Test
    @DisplayName("全灭异常（裸/被包装）→「AI 服务暂时不可用，请联系管理员。」；其余异常沿用既有文案")
    void sseFallbackMessageMappings() {
        ChatService service = newService(mock(HarnessAgent.class), mock(IntentCascadeRouter.class));
        assertEquals("AI 服务暂时不可用，请联系管理员。",
                service.sseFallbackMessage(new AllProvidersOpenException(
                        "main 全部 provider 熔断（tried=[glm, kimi]）")));
        // Reactor/框架包装后的 cause 链也应识别
        assertEquals("AI 服务暂时不可用，请联系管理员。",
                service.sseFallbackMessage(new RuntimeException("stream failed",
                        new AllProvidersOpenException("reviewer 全部 provider 熔断"))));
        assertTrue(service.sseFallbackMessage(new RuntimeException("401 unauthorized"))
                .contains("认证异常"));
        assertTrue(service.sseFallbackMessage(new RuntimeException("Arrearage: account not in good standing"))
                .contains("余额不足"));
        assertTrue(service.sseFallbackMessage(new RuntimeException("普通业务异常"))
                .contains("处理失败"));
    }
}
