package com.travelscope.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.travelscope.agent.IntentCascadeRouter;
import com.travelscope.collab.CollabFileReader;
import com.travelscope.collab.CollabFileWriter;
import com.travelscope.collab.IntakeResult;
import com.travelscope.collab.TaskBacklog;
import com.travelscope.collab.TaskWorkspace;
import com.travelscope.common.TracingHelper;
import com.travelscope.config.AppProperties;
import com.travelscope.dto.IntentResult;
import com.travelscope.entity.Conversation;
import com.travelscope.service.workflow.RequirementWorkflowService;
import com.travelscope.service.workflow.TaskBacklogPlanner;
import com.travelscope.service.workflow.dto.CollectOutcome;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatService review_report SSE 事件（S3.1 JSON 数据源）测试。
 * <p>
 * 打桩与捕获套路照搬 {@link ChatServicePlanningOrchestrationTest}：依赖全 Mockito mock，
 * TaskWorkspace 真实（@TempDir 落盘预置协作产物），SSE 经反射读取
 * ResponseBodyEmitter.earlySendAttempts 缓冲捕获，轮询等 done。
 * </p>
 */
@DisplayName("review_report SSE 事件（JSON 数据源）")
class ChatServiceReviewEventTest {

    @TempDir
    Path tempDir;

    private HarnessAgent travelMasterAgent;
    private IntentCascadeRouter intentCascadeRouter;
    private ConversationService conversationService;
    private TaskWorkspace workspace;
    private TripRequirementStore tripRequirementStore;
    private LlmGateway llmGateway;
    private RequirementWorkflowService requirementWorkflowService;
    private TaskBacklogPlanner taskBacklogPlanner;
    private HarnessAgent itineraryPlanningAgent;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        travelMasterAgent = mock(HarnessAgent.class);
        intentCascadeRouter = mock(IntentCascadeRouter.class);
        conversationService = mock(ConversationService.class);
        workspace = new TaskWorkspace(new CollabFileWriter(tempDir.toString()), new CollabFileReader());
        tripRequirementStore = mock(TripRequirementStore.class);
        llmGateway = mock(LlmGateway.class);
        LoadShedService loadShedService = mock(LoadShedService.class);
        ConversationSummaryService conversationSummaryService = mock(ConversationSummaryService.class);
        Model gatewayChatModel = mock(Model.class);
        PointsService pointsService = mock(PointsService.class);
        requirementWorkflowService = mock(RequirementWorkflowService.class);
        taskBacklogPlanner = mock(TaskBacklogPlanner.class);
        itineraryPlanningAgent = mock(HarnessAgent.class, RETURNS_DEEP_STUBS);
        RagService ragService = mock(RagService.class);

        when(llmGateway.tryAcquire(anyString())).thenReturn(LlmGateway.AcquireResult.ok());
        when(llmGateway.laneTimeout(anyBoolean())).thenReturn(Duration.ofSeconds(600));
        when(pointsService.costOf(anyBoolean())).thenReturn(10);
        when(pointsService.tryDeduct(any(), anyInt())).thenReturn(true);
        when(loadShedService.tryAdmit(anyBoolean())).thenReturn(null);
        when(conversationService.listMessages(any())).thenReturn(List.of());

        chatService = new ChatService(travelMasterAgent, intentCascadeRouter, ragService,
                conversationService, workspace, tripRequirementStore, llmGateway, loadShedService,
                conversationSummaryService, TracingHelper.NOOP, new AppProperties(),
                gatewayChatModel, pointsService, requirementWorkflowService, taskBacklogPlanner,
                itineraryPlanningAgent);
    }

    // ---------- 用例 1：passed 场景 → 事件载荷 {passed:true, total:85, segments:[]} ----------

    @Test
    @DisplayName("passed 产物存在 → review_report 事件载荷 total=85")
    void passedArtifactEmitsPayload() throws Exception {
        seedPlanningChain();
        // 规则质检打桩：step5 编排调用时写实 review_passed（对齐真实链路：规则服务写产物、渲染层读产物；
        // 预置静态文件会被编排起点的跨轮清理删掉）
        RuleReviewService stub = mock(RuleReviewService.class);
        when(stub.check(anyString(), anyString())).thenAnswer(inv -> {
            workspace.writeReviewPassed(inv.getArgument(0), inv.getArgument(1),
                    new com.travelscope.collab.ReviewPassed(85, 16, List.of("核验一致"), "2026-10-19 12:00:00"));
            return new RuleReviewService.Outcome(true, List.of(), null);
        });
        org.springframework.test.util.ReflectionTestUtils.setField(chatService, "ruleReviewService", stub);

        SseEmitter emitter = chatService.streamChat(conversation(), 1L, "帮我规划杭州两日游");
        List<String> items = awaitEvent(emitter, "done");

        assertThat(eventNames(items)).contains("review_report");
        JSONObject payload = JSON.parseObject(payloadOf(items, "review_report"));
        assertThat(payload.getBooleanValue("passed")).isTrue();
        assertThat(payload.getIntValue("total")).isEqualTo(85);
        assertThat(payload.getJSONArray("segments")).isNotNull().isEmpty();
        // done 标注同源不回归
        assertThat(payloadOf(items, "done")).contains("✅ 已通过质量审阅（评分 85/100）");
    }

    // ---------- 用例 2：两产物都缺 → 不发 review_report 事件 ----------

    @Test
    @DisplayName("缺 review 产物 → 不发 review_report 事件，done 照常")
    void missingArtifactsSkipsEvent() throws Exception {
        seedPlanningChain();
        // 不写任何 review 产物

        SseEmitter emitter = chatService.streamChat(conversation(), 1L, "帮我规划杭州两日游");
        List<String> items = awaitEvent(emitter, "done");

        assertThat(eventNames(items)).doesNotContain("review_report");
        assertThat(payloadOf(items, "done")).contains("杭州两日游完整方案");
    }

    // ---------- 公共打桩与 SSE 捕获（与 ChatServicePlanningOrchestrationTest 同款） ----------

    /** PLANNING 编排链打桩 + 预置 intake_result.json / itinerary_draft.md（不含 review 产物） */
    private void seedPlanningChain() throws Exception {
        workspace.writeIntakeResult("1", "conv-13", intakeResult());
        // JSON 草案（规则质检只读 JSON 契约；旧 MD 仅渲染层兜底）
        workspace.writeItineraryDraft("1", "conv-13", new com.travelscope.collab.ItineraryDraft(
                List.of(new com.travelscope.collab.ItineraryDraft.DraftDay(1, "2026-10-24", "西湖环线",
                        List.of(new com.travelscope.collab.ItineraryDraft.DraftItem(
                                "09:00-11:30", "西湖环湖", "西湖", "免费", null)))),
                null, List.of(), List.of(), "# 杭州两日游完整方案\nDay1 西湖环湖。"));
        when(intentCascadeRouter.classify(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IntentResult("PLANNING", "完整行程规划"));
        when(requirementWorkflowService.handle(anyString(), anyString(), anyString()))
                .thenReturn(CollectOutcome.collected());
        when(taskBacklogPlanner.plan(eq("1"), any(IntakeResult.class))).thenReturn(backlog());
        when(itineraryPlanningAgent.streamEvents(any(UserMessage.class), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());
    }

    private static Conversation conversation() {
        Conversation c = new Conversation();
        c.setId(13L);
        return c;
    }

    private static IntakeResult intakeResult() {
        return new IntakeResult("conv-13", IntakeResult.Status.DONE, 0,
                new IntakeResult.RequirementFields(
                        fv("杭州"), fv("2"), fv("2026-10-24"), fv("上海"),
                        fv(null), fv(null), fv(null), fv(null)),
                false, "2026-10-19 10:00:00");
    }

    private static IntakeResult.FieldValue fv(String value) {
        return new IntakeResult.FieldValue(value, value != null, null);
    }

    private static TaskBacklog backlog() {
        return new TaskBacklog("conv-13", List.of(
                item("T1"), item("T2"), item("T3"), item("T4"), item("T5")));
    }

    private static TaskBacklog.TaskItem item(String id) {
        return new TaskBacklog.TaskItem(id, "任务 " + id, null, "P0", "PENDING",
                "2026-10-19 10:00:00");
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

    /** 轮询等待指定 SSE 事件出现（编排链在泳道线程异步发射），返回当时全部条目 */
    private static List<String> awaitEvent(SseEmitter emitter, String eventName) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            List<String> items = sentItems(emitter);
            if (eventNames(items).contains(eventName)) {
                return items;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待 SSE 事件超时: " + eventName + "，已收到: " + sentItems(emitter));
    }

    /** 事件名序列（取 "event:" 后至首个 \n 前的段） */
    private static List<String> eventNames(List<String> items) {
        return items.stream().map(String::trim)
                .filter(s -> s.startsWith("event:"))
                .map(s -> {
                    String rest = s.substring("event:".length());
                    int nl = rest.indexOf('\n');
                    return nl >= 0 ? rest.substring(0, nl) : rest;
                })
                .toList();
    }

    /** 指定事件的载荷（"event:{name}\ndata:" 条目的下一条目即 data） */
    private static String payloadOf(List<String> items, String eventName) {
        for (int i = 0; i < items.size() - 1; i++) {
            String trimmed = items.get(i).trim();
            if (trimmed.startsWith("event:")) {
                String rest = trimmed.substring("event:".length());
                int nl = rest.indexOf('\n');
                String name = nl >= 0 ? rest.substring(0, nl) : rest;
                if (name.equals(eventName)) {
                    return items.get(i + 1);
                }
            }
        }
        return null;
    }
}
