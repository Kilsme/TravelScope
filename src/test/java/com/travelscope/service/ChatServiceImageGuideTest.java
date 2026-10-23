package com.travelscope.service;

import com.travelscope.agent.IntentCascadeRouter;
import com.travelscope.collab.TaskWorkspace;
import com.travelscope.common.TracingHelper;
import com.travelscope.config.AppProperties;
import com.travelscope.dto.IntentResult;
import com.travelscope.service.workflow.RequirementFormSpec;
import com.travelscope.service.workflow.RequirementWorkflowService;
import com.travelscope.service.workflow.TaskBacklogPlanner;
import com.travelscope.service.workflow.dto.CollectOutcome;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 私人讲解员（图片提问）链路测试（2026-10-22）：
 * <ol>
 *   <li>场景一（带图 + 非 PLANNING）：intent → 讲解 delta → done；图片上传 MinIO
 *       （chat-images/ 前缀）、content_blocks 存对象引用（非 base64）、content 为占位文本、
 *       不经 master Agent；</li>
 *   <li>场景二（带图 + PLANNING）：讲解 delta 先行、requirement_form 随后（顺序断言），
 *       需求采集收到的输入含讲解文本（表单可预填目的地）；</li>
 *   <li>空文本 + 图片走缺省问题「讲解一下这个景点」（意图分类输入与落库一致）；</li>
 *   <li>讲解调用失败 → error 事件（sseFallbackMessage 口径）+ 正常收尾。</li>
 * </ol>
 * 桩口径同 ChatServiceSseFallbackMessageTest（mock 全依赖 + earlySendAttempts 读 SSE）。
 */
class ChatServiceImageGuideTest {

    /** 最小 JPEG 魔数 base64（FF D8 FF E0 … 即 /9j/4… 开头，魔数嗅探按 image/jpeg 放行） */
    private static final String JPEG_BASE64 = "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwcJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPDs0NDT/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAv/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEBAQAAAAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIRAxEAPwA/8A8A/9k=";

    private static final String GUIDE_TEXT = "照片中是杭州西湖的断桥残雪，白堤起点……";

    private HarnessAgent masterAgent;
    private Model gatewayChatModel;
    private Model guideModel;
    private MinioService minioService;
    private ConversationService conversationService;
    private RequirementWorkflowService requirementWorkflowService;
    private TaskWorkspace taskWorkspace;
    private ChatService newService(IntentCascadeRouter router) {
        masterAgent = mock(HarnessAgent.class);
        gatewayChatModel = mock(Model.class);
        guideModel = mock(Model.class);
        minioService = mock(MinioService.class);
        conversationService = mock(ConversationService.class);
        requirementWorkflowService = mock(RequirementWorkflowService.class);
        taskWorkspace = mock(TaskWorkspace.class);
        ChatService service = new ChatService(masterAgent, router,
                mock(RagService.class), conversationService, taskWorkspace,
                mock(TripRequirementStore.class), mock(LlmGateway.class), mock(LoadShedService.class),
                mock(ConversationSummaryService.class), TracingHelper.NOOP, new AppProperties(),
                gatewayChatModel, mock(PointsService.class), requirementWorkflowService,
                mock(TaskBacklogPlanner.class), mock(HarnessAgent.class), minioService, guideModel);
        return service;
    }

    private void stubAdmission(ChatService service) throws Exception {
        var llmGateway = (LlmGateway) field(service, "llmGateway");
        when(llmGateway.tryAcquire(anyString())).thenReturn(LlmGateway.AcquireResult.ok());
        when(llmGateway.laneTimeout(anyBoolean())).thenReturn(Duration.ofSeconds(25));
        var points = (PointsService) field(service, "pointsService");
        when(points.costOf(anyBoolean())).thenReturn(10);
        when(points.tryDeduct(any(), anyInt())).thenReturn(true);
        var loadShed = (LoadShedService) field(service, "loadShedService");
        when(loadShed.tryAdmit(anyBoolean())).thenReturn(null);
        when(conversationService.listMessages(any())).thenReturn(List.of());
    }

    private static Object field(ChatService service, String name) throws Exception {
        Field f = ChatService.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(service);
    }

    /** 讲解模型流：两个文本块 */
    private static ChatResponse guideChunk(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

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
            if (items.stream().map(String::trim)
                    .anyMatch(s -> s.equals("event:" + eventName) || s.startsWith("event:" + eventName + "\n"))) {
                return items;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待 SSE 事件超时: " + eventName + "，已收到: " + sentItems(emitter));
    }

    private com.travelscope.entity.Conversation conversation() {
        com.travelscope.entity.Conversation c = new com.travelscope.entity.Conversation();
        c.setId(901L);
        c.setUserId(1L);
        return c;
    }

    @Test
    @DisplayName("场景一：带图+CHAT → intent→delta(讲解)→done；MinIO chat-images/ 前缀上传；"
            + "content_blocks 存对象引用非 base64；不经 master Agent")
    void scenario1ImageChatStreamsGuideDeltas() throws Exception {
        IntentCascadeRouter router = mock(IntentCascadeRouter.class);
        when(router.classify(anyString(), any(), anyString(), anyString()))
                .thenReturn(new IntentResult("CHAT", "图片讲解"));
        ChatService service = newService(router);
        stubAdmission(service);
        when(guideModel.stream(anyList(), anyList(), any()))
                .thenReturn(Flux.just(guideChunk(GUIDE_TEXT), guideChunk("建议清晨游览。")));

        SseEmitter emitter = ChatExecutionTestSupport.stream(service, conversation(), 1L, "这是哪里", JPEG_BASE64);
        List<String> items = awaitEvent(emitter, "done");
        String flattened = String.join("\n", items);

        assertTrue(flattened.contains("event:intent"), "intent 事件不缺: " + items);
        assertTrue(flattened.contains(GUIDE_TEXT), "讲解 delta 应流出: " + items);
        assertTrue(flattened.contains("建议清晨游览"), "多块讲解均流出: " + items);
        assertTrue(flattened.contains("event:done"), "done 事件不缺");
        verifyNoInteractions(masterAgent);

        // Durable admission owns upload and message persistence; execution cannot repeat them.
        verifyNoInteractions(minioService);
        verify(conversationService, never()).saveUserMessage(any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("场景二：带图+PLANNING → 讲解 delta 先于 requirement_form；需求采集输入含讲解文本（预填依据）")
    void scenario2ImagePlanningGuidesThenForm() throws Exception {
        IntentCascadeRouter router = mock(IntentCascadeRouter.class);
        when(router.classify(anyString(), any(), anyString(), anyString()))
                .thenReturn(new IntentResult("PLANNING", "图片行程规划"));
        ChatService service = newService(router);
        stubAdmission(service);
        when(guideModel.stream(anyList(), anyList(), any()))
                .thenReturn(Flux.just(guideChunk(GUIDE_TEXT)));
        when(taskWorkspace.readIntakeResult(anyString(), anyString())).thenReturn(Optional.empty());
        when(requirementWorkflowService.handle(anyString(), anyString(), anyString()))
                .thenReturn(CollectOutcome.needUserInput(
                        List.of(new RequirementFormSpec.FieldSpec("destination", "目的地", "text",
                                null, false, true, List.of(), "如：杭州")),
                        "请补全目的地"));

        SseEmitter emitter = ChatExecutionTestSupport.stream(service, conversation(), 1L, "帮我规划去这里的三天行程", JPEG_BASE64);
        List<String> items = awaitEvent(emitter, "requirement_form");
        // 等 done 收尾（handleComplete 发 done）
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && items.stream().noneMatch(s -> s.trim().startsWith("event:done"))) {
            Thread.sleep(50);
            items = sentItems(emitter);
        }
        String flattened = String.join("\n", items);

        assertTrue(flattened.contains(GUIDE_TEXT), "讲解 delta 应先流出: " + items);
        assertTrue(flattened.contains("event:requirement_form"), "需求表单随后推送: " + items);
        int guideIdx = flattened.indexOf(GUIDE_TEXT);
        int formIdx = flattened.indexOf("event:requirement_form");
        assertTrue(guideIdx >= 0 && formIdx > guideIdx, "讲解先于表单（任务拆解顺序）");
        verifyNoInteractions(masterAgent);

        // 需求采集输入 = 原文 + 讲解文本（截断 800 字内）——表单预填的数据源
        ArgumentCaptor<String> handleInput = ArgumentCaptor.forClass(String.class);
        verify(requirementWorkflowService).handle(handleInput.capture(), anyString(), anyString());
        assertTrue(handleInput.getValue().contains("帮我规划去这里的三天行程"), "保留用户原文");
        assertTrue(handleInput.getValue().contains("【图片讲解】"), "讲解文本作为抽取输入");
        assertTrue(handleInput.getValue().contains(GUIDE_TEXT), "讲解内容进入抽取输入");
    }

    @Test
    @DisplayName("空文本+图片：缺省问题「讲解一下这个景点」——意图分类输入与落库占位一致")
    void blankTextWithImageUsesDefaultQuestion() throws Exception {
        IntentCascadeRouter router = mock(IntentCascadeRouter.class);
        when(router.classify(anyString(), any(), anyString(), anyString()))
                .thenReturn(new IntentResult("CHAT", "默认讲解"));
        ChatService service = newService(router);
        stubAdmission(service);
        when(guideModel.stream(anyList(), anyList(), any()))
                .thenReturn(Flux.just(guideChunk(GUIDE_TEXT)));

        SseEmitter emitter = ChatExecutionTestSupport.stream(service, conversation(), 1L, "", JPEG_BASE64);
        awaitEvent(emitter, "done");

        // 意图分类收到的输入是补齐后的问题文本（非空串）
        ArgumentCaptor<String> classifyInput = ArgumentCaptor.forClass(String.class);
        verify(router).classify(classifyInput.capture(), any(), anyString(), anyString());
        assertEquals("讲解一下这个景点", classifyInput.getValue());
        verify(conversationService, never()).saveUserMessage(any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("讲解调用失败 → error 结束，不能再成功收尾")
    void guideFailureMapsToErrorEvent() throws Exception {
        IntentCascadeRouter router = mock(IntentCascadeRouter.class);
        when(router.classify(anyString(), any(), anyString(), anyString()))
                .thenReturn(new IntentResult("CHAT", "图片讲解"));
        ChatService service = newService(router);
        stubAdmission(service);
        when(guideModel.stream(anyList(), anyList(), any()))
                .thenReturn(Flux.error(new RuntimeException("讲解模型不可用")));

        SseEmitter emitter = ChatExecutionTestSupport.stream(service, conversation(), 1L, "这是哪里", JPEG_BASE64);
        List<String> items = awaitEvent(emitter, "error");
        String flattened = String.join("\n", items);
        assertTrue(flattened.contains("event:error"), "失败转 error 事件: " + items);
        assertTrue(!flattened.contains("event:done"), "失败不能按成功收尾");
        assertTrue(flattened.contains("处理失败"), "sseFallbackMessage 口径文案: " + items);
    }

    @Test
    @DisplayName("无图路径零改动：纯文本 CHAT 仍走 master Agent（runAgent 原路径），MinIO 零调用")
    void textOnlyTurnStillRunsMasterAgent() throws Exception {
        IntentCascadeRouter router = mock(IntentCascadeRouter.class);
        when(router.classify(anyString(), any(), anyString(), anyString()))
                .thenReturn(new IntentResult("CHAT", "闲聊"));
        ChatService service = newService(router);
        stubAdmission(service);
        when(masterAgent.streamEvents(any(io.agentscope.core.message.UserMessage.class), any())).thenReturn(Flux.empty());

        ChatExecutionTestSupport.stream(service, conversation(), 1L, "你好", null);
        // runAgent 在快泳道异步订阅——轮询等待 master Agent 被调起（未调起超时由最终 verify 抛出）
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                verify(masterAgent).streamEvents(any(io.agentscope.core.message.UserMessage.class), any());
                break;
            } catch (AssertionError notYet) {
                Thread.sleep(50);
            }
        }
        verify(masterAgent).streamEvents(any(io.agentscope.core.message.UserMessage.class), any());
        verify(minioService, never()).upload(anyString(), any(), anyLong(), anyString());
        // 无图轮走 3 参落库（无 content_blocks）
        verify(conversationService, never()).saveUserMessage(any(), any(), anyString());
    }
    @Test void englishImageReplyUsesPerTurnSystemInstruction() throws Exception {
        IntentCascadeRouter router=mock(IntentCascadeRouter.class);
        when(router.classify(anyString(),any(),anyString(),anyString())).thenReturn(new IntentResult("TOOL_CALL","image"));
        ChatService service=newService(router);stubAdmission(service);
        when(guideModel.stream(anyList(),anyList(),any())).thenReturn(Flux.just(guideChunk("A garden.")));
        var execution=service.execution(conversation(),1L,"Explain this image",JPEG_BASE64,java.util.UUID.randomUUID(),"en",cost->true);
        execution.events.blockLast(Duration.ofSeconds(5));
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Msg>> messages=ArgumentCaptor.forClass(List.class);
        verify(guideModel).stream(messages.capture(),anyList(),any());
        assertTrue(messages.getValue().getFirst().getTextContent().contains("Use English"));
        assertEquals("A garden.",service.executionResult(execution).content());
    }

}
