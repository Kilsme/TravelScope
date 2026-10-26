package com.travelscope.service;

import com.travelscope.agent.IntentCascadeRouter;
import com.travelscope.assessment.ObservationContext;
import com.travelscope.assessment.ObservationStore;
import com.travelscope.collab.CollabFileReader;
import com.travelscope.collab.CollabFileWriter;
import com.travelscope.collab.IntakeResult;
import com.travelscope.collab.TaskBacklog;
import com.travelscope.collab.TaskWorkspace;
import com.travelscope.common.TracingHelper;
import com.travelscope.config.AppProperties;
import com.travelscope.dto.IntentResult;
import com.travelscope.dto.TripRequirementState;
import com.travelscope.entity.Conversation;
import com.travelscope.service.workflow.RequirementFormSpec;
import com.travelscope.service.workflow.RequirementWorkflowService;
import com.travelscope.service.workflow.TaskBacklogPlanner;
import com.travelscope.service.workflow.dto.CollectOutcome;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ChatService PLANNING 五步编排（S2.5）测试——全仓首个构造 ChatService 实例的单测。
 * workflow / 任务拆分器 / planning-agent / master 全部 Mockito mock；TaskWorkspace 真实
 * （@TempDir 落盘预置协作产物）。SSE 事件经反射读取 ResponseBodyEmitter.earlySendAttempts
 * 捕获（无 MVC handler 时 send 全部缓冲于此字段），轮询等 done 事件出现。
 */
class ChatServicePlanningOrchestrationTest {

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
    private ConversationSummaryService conversationSummaryService;
    private PointsService pointsService;
    private ObservationStore observationStore;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        travelMasterAgent = mock(HarnessAgent.class, RETURNS_DEEP_STUBS);
        intentCascadeRouter = mock(IntentCascadeRouter.class);
        conversationService = mock(ConversationService.class);
        workspace = new TaskWorkspace(new CollabFileWriter(tempDir.toString()), new CollabFileReader());
        tripRequirementStore = mock(TripRequirementStore.class);
        llmGateway = mock(LlmGateway.class);
        LoadShedService loadShedService = mock(LoadShedService.class);
        conversationSummaryService = mock(ConversationSummaryService.class);
        Model gatewayChatModel = mock(Model.class);
        pointsService = mock(PointsService.class);
        requirementWorkflowService = mock(RequirementWorkflowService.class);
        taskBacklogPlanner = mock(TaskBacklogPlanner.class);
        RagService ragService = mock(RagService.class);

        // 准入/积分/减负全放行；泳道超时 600s（与 application.yml 一致）
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
                mock(io.agentscope.core.model.Model.class));
    }

    // ---------- 用例 1：缺项轮 → 只发 requirement_form（不进 step2，Agent 零交互） ----------
    @Test
    void missingFieldsTurnEmitsOnlyFormEvent() throws Exception {
        when(intentCascadeRouter.classify(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IntentResult("PLANNING", "完整行程规划"));
        when(requirementWorkflowService.handle(anyString(), anyString(), anyString()))
                .thenReturn(CollectOutcome.needUserInput(List.of(
                        new RequirementFormSpec.FieldSpec("fromCity", "出发城市", "text", null,
                                false, true, List.of(), "如：上海")), "请补全出发城市"));

        SseEmitter emitter = ChatExecutionTestSupport.stream(chatService, conversation(), 1L, "帮我规划杭州两日游");
        List<String> items = awaitEvent(emitter, "done");

        assertThat(eventNames(items)).containsExactly("intent", "requirement_form", "done");
        assertThat(payloadOf(items, "requirement_form")).contains("fromCity");
        assertThat(payloadOf(items, "done")).isEqualTo("请确认以下行程需求，可直接修改后开始规划。");
        verifyNoInteractions(taskBacklogPlanner, travelMasterAgent);
    }

    // ---------- 用例 2：收齐轮 → 按序 step2→step5，方案带「✅ 已通过质量审阅」标注 ----------
    @Test
    void collectedTurnRunsFullChainInOrder() throws Exception {
        seedIntakeAndArtifacts(true);
        when(intentCascadeRouter.classify(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IntentResult("PLANNING", "完整行程规划"));
        when(requirementWorkflowService.handle(anyString(), anyString(), anyString()))
                .thenReturn(CollectOutcome.collected());
        when(taskBacklogPlanner.plan(eq("1"), any(IntakeResult.class))).thenReturn(backlog());
        when(travelMasterAgent.streamEvents(any(UserMessage.class), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());

        SseEmitter emitter = ChatExecutionTestSupport.stream(chatService, conversation(), 1L, "帮我规划杭州两日游");
        List<String> items = awaitEvent(emitter, "done");

        // 编排顺序：step1 workflow.handle → step2 planner.plan → step3 itinerary.streamEvents
        InOrder order = inOrder(requirementWorkflowService, taskBacklogPlanner, travelMasterAgent);
        order.verify(requirementWorkflowService).handle("帮我规划杭州两日游", "1", "conv-13");
        order.verify(taskBacklogPlanner).plan(eq("1"), any(IntakeResult.class));
        order.verify(travelMasterAgent).streamEvents(any(UserMessage.class), any(RuntimeContext.class));

        // 渲染分段化（v6 阶段五）：种子草案有 days 结构 → step5 补推两条逐天事件
        // （旧正则路径在该无「## Day」标题的正文上切不出节，零事件）
        assertThat(eventNames(items)).containsExactly("intent", "agent_status",
                "itinerary_day", "itinerary_day", "review_report", "done");
        // step2 里程碑由编排代码显式发射（卡片点 4）
        assertThat(payloadOf(items, "agent_status")).contains("任务清单已登记").contains("5 项");
        // step5：渲染 itinerary_draft.md + 代码生成质检标注
        assertThat(payloadOf(items, "done")).contains("杭州两日游完整方案")
                .contains("✅ 已通过质量审阅（评分 91/100）");
        // S3.1：review_report 事件载荷为 JSON（passed/total 契约字段）
        com.alibaba.fastjson2.JSONObject report = com.alibaba.fastjson2.JSON
                .parseObject(payloadOf(items, "review_report"));
        assertThat(report.getBooleanValue("passed")).isTrue();
        assertThat(report.getIntValue("total")).isEqualTo(91);
    }

    // ---------- 用例 3：无 review_passed（回炉超限）→ R4 降级交付：⚠️ 标注带未解决数、载荷 DEGRADED ----------
    @Test
    void missingReviewPassedAnnotatesBestEffort() throws Exception {
        seedIntakeAndArtifacts(false);
        when(intentCascadeRouter.classify(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IntentResult("PLANNING", "完整行程规划"));
        when(requirementWorkflowService.handle(anyString(), anyString(), anyString()))
                .thenReturn(CollectOutcome.collected());
        when(taskBacklogPlanner.plan(eq("1"), any(IntakeResult.class))).thenReturn(backlog());
        when(travelMasterAgent.streamEvents(any(UserMessage.class), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());

        SseEmitter emitter = ChatExecutionTestSupport.stream(chatService, conversation(), 1L, "帮我规划杭州两日游");
        List<String> items = awaitEvent(emitter, "done");

        // R4：终检 FAIL → 编排层标记 DEGRADED，标注升级为「评分 + 未解决问题数」指针
        assertThat(payloadOf(items, "done")).contains("杭州两日游完整方案")
                .contains("⚠️ 当前最佳版本（已尽力，评分 72/100，1 项未解决问题，详见质检明细）");
        // S3.1：回炉超限场景载荷 passed=false，分段明细带 pass 标志；R4 增 deliveryMode+issues
        com.alibaba.fastjson2.JSONObject report = com.alibaba.fastjson2.JSON
                .parseObject(payloadOf(items, "review_report"));
        assertThat(report.getBooleanValue("passed")).isFalse();
        assertThat(report.getIntValue("total")).isEqualTo(72);
        assertThat(report.getString("deliveryMode")).isEqualTo("DEGRADED");
        assertThat(report.getJSONArray("issues")).hasSize(1);
        assertThat(report.getJSONArray("segments")).hasSize(3)
                .anySatisfy(s -> {
                    com.alibaba.fastjson2.JSONObject seg = (com.alibaba.fastjson2.JSONObject) s;
                    assertThat(seg.getIntValue("segment")).isEqualTo(2);
                    assertThat(seg.getBooleanValue("pass")).isFalse();
                });
        verify(travelMasterAgent).streamEvents(any(UserMessage.class), any(RuntimeContext.class));
    }

    // ---------- 用例 4：取消轮 → 本轮结束，不进 step2 ----------
    @Test
    void cancelledTurnEndsWithoutPlanning() throws Exception {
        when(intentCascadeRouter.classify(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IntentResult("PLANNING", "完整行程规划"));
        when(requirementWorkflowService.handle(anyString(), anyString(), anyString()))
                .thenReturn(CollectOutcome.cancelled());

        SseEmitter emitter = ChatExecutionTestSupport.stream(chatService, conversation(), 1L, "取消规划");
        List<String> items = awaitEvent(emitter, "done");

        assertThat(eventNames(items)).containsExactly("intent", "done");
        assertThat(payloadOf(items, "done")).isEqualTo("已取消规划。");
        verifyNoInteractions(taskBacklogPlanner, travelMasterAgent);
    }

    // ---------- 用例 5：收集期意图保护——CHAT 分类 + COLLECTING 状态 → 强制 PLANNING 编排 ----------
    @Test
    void chatDuringCollectingForcedIntoOrchestration() throws Exception {
        TripRequirementState state = new TripRequirementState();
        state.status = TripRequirementState.Status.COLLECTING;
        state.destination = "杭州";
        when(tripRequirementStore.get("1", "conv-13")).thenReturn(state);
        when(intentCascadeRouter.classify(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IntentResult("CHAT", "对卡片项的短回答"));
        when(requirementWorkflowService.handle(anyString(), anyString(), anyString()))
                .thenReturn(CollectOutcome.needUserInput(List.of(), null));

        SseEmitter emitter = ChatExecutionTestSupport.stream(chatService, conversation(), 1L, "杭州");
        List<String> items = awaitEvent(emitter, "done");

        // 短回答没有落到 master，而是被意图保护拽进编排 step1
        assertThat(eventNames(items)).contains("requirement_form");
        InOrder order = inOrder(requirementWorkflowService);
        order.verify(requirementWorkflowService).handle("杭州", "1", "conv-13");
        verifyNoInteractions(taskBacklogPlanner, travelMasterAgent);
    }

    // ---------- 用例 6：评审产物 JSON → ✅ 标注照常；S3.1 起 review_report 事件同源于 JSON ----------
    @Test
    void jsonReviewPassedDrivesAnnotation() throws Exception {
        seedIntakeAndArtifacts(true);
        when(intentCascadeRouter.classify(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IntentResult("PLANNING", "完整行程规划"));
        when(requirementWorkflowService.handle(anyString(), anyString(), anyString()))
                .thenReturn(CollectOutcome.collected());
        when(taskBacklogPlanner.plan(eq("1"), any(IntakeResult.class))).thenReturn(backlog());
        when(travelMasterAgent.streamEvents(any(UserMessage.class), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());

        SseEmitter emitter = ChatExecutionTestSupport.stream(chatService, conversation(), 1L, "帮我规划杭州两日游");
        List<String> items = awaitEvent(emitter, "done");

        assertThat(payloadOf(items, "done")).contains("杭州两日游完整方案")
                .contains("✅ 已通过质量审阅（评分 91/100）");
        // S3.1：review_report SSE 事件已切 JSON 读取，与标注同源
        assertThat(eventNames(items)).contains("review_report");
        com.alibaba.fastjson2.JSONObject report = com.alibaba.fastjson2.JSON
                .parseObject(payloadOf(items, "review_report"));
        assertThat(report.getBooleanValue("passed")).isTrue();
        assertThat(report.getIntValue("total")).isEqualTo(91);
    }

    // ==================== 契约内联与二次规划（2026-10-20 晚） ====================

    // ---------- 缓存预检上提：命中项内联进委派说明 + 对应任务标 DONE（planner 首轮只查未命中项） ----------
    @Test
    void cachePrecheckInlinesHitsAndMarksDone() throws Exception {
        seedIntakeAndArtifacts(true);
        // 编排层预检：weather/hotel 命中、poi 未命中（部分命中路径）
        TaskResultCache cache = mock(TaskResultCache.class);
        when(cache.get("1", "conv-13", TaskResultCache.TaskType.WEATHER)).thenReturn("杭州 10-24 多云 20℃");
        when(cache.get("1", "conv-13", TaskResultCache.TaskType.HOTEL)).thenReturn("西湖边民宿 ¥300");
        TaskRegistry registry = mock(TaskRegistry.class);
        ReflectionTestUtils.setField(chatService, "taskResultCache", cache);
        ReflectionTestUtils.setField(chatService, "taskRegistry", registry);
        when(intentCascadeRouter.classify(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IntentResult("PLANNING", "完整行程规划"));
        when(requirementWorkflowService.handle(anyString(), anyString(), anyString()))
                .thenReturn(CollectOutcome.collected());
        when(taskBacklogPlanner.plan(eq("1"), any(IntakeResult.class))).thenReturn(backlog());
        when(travelMasterAgent.streamEvents(any(UserMessage.class), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());

        awaitEvent(ChatExecutionTestSupport.stream(chatService, conversation(), 1L, "帮我规划杭州两日游"), "done");

        ArgumentCaptor<UserMessage> delegated = ArgumentCaptor.forClass(UserMessage.class);
        verify(travelMasterAgent).streamEvents(delegated.capture(), any(RuntimeContext.class));
        String text = delegated.getValue().getTextContent();
        // 命中节内联了天气/酒店内容，且声明禁止再调对应工具与预检
        assertThat(text).contains("【缓存命中").contains("杭州 10-24 多云 20℃").contains("西湖边民宿 ¥300")
                .contains("禁止再调 getWeatherForecast").contains("禁止再调 searchHotels");
        // POI 未命中 → 不出现在命中节
        assertThat(text).doesNotContain("POI 候选池（已就绪");
        // 命中项对应任务标 DONE；POI（T3）不在标 DONE 之列
        verify(registry).updateStatus("1", "conv-13", "T2", "DONE");
        verify(registry).updateStatus("1", "conv-13", "T4", "DONE");
        verify(registry, org.mockito.Mockito.never()).updateStatus(eq("1"), eq("conv-13"), eq("T3"), anyString());
    }

    // ---------- DONE 会话上的新 PLANNING：需求状态机重开为 COLLECTING 交给 workflow 增量合并 ----------
    @Test
    void planningOnDoneConversationReopensRequirementForMerge() throws Exception {
        TripRequirementState doneState = new TripRequirementState();
        doneState.status = TripRequirementState.Status.DONE;
        doneState.destination = "苏州";
        when(tripRequirementStore.get("1", "conv-13")).thenReturn(doneState);
        seedIntakeAndArtifacts(true);
        when(intentCascadeRouter.classify(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IntentResult("PLANNING", "完整行程规划"));
        when(requirementWorkflowService.handle(anyString(), anyString(), anyString()))
                .thenReturn(CollectOutcome.collected());
        when(taskBacklogPlanner.plan(eq("1"), any(IntakeResult.class))).thenReturn(backlog());
        when(travelMasterAgent.streamEvents(any(UserMessage.class), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());

        awaitEvent(ChatExecutionTestSupport.stream(chatService, conversation(), 1L, "帮我规划西安四日游"), "done");

        // 状态机被重开为 COLLECTING 并保存（旧字段保留待 workflow 增量合并）
        ArgumentCaptor<TripRequirementState> saved = ArgumentCaptor.forClass(TripRequirementState.class);
        verify(tripRequirementStore).save(eq("1"), eq("conv-13"), saved.capture());
        assertThat(saved.getValue().status).isEqualTo(TripRequirementState.Status.COLLECTING);
        assertThat(saved.getValue().destination).isEqualTo("苏州");
    }

    // ---------- 委派说明内联契约：planner 首轮免 read_file ----------
    @Test
    void delegationMessageInlinesContracts() throws Exception {
        seedIntakeAndArtifacts(true);
        when(intentCascadeRouter.classify(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IntentResult("PLANNING", "完整行程规划"));
        when(requirementWorkflowService.handle(anyString(), anyString(), anyString()))
                .thenReturn(CollectOutcome.collected());
        when(taskBacklogPlanner.plan(eq("1"), any(IntakeResult.class))).thenReturn(backlog());
        when(travelMasterAgent.streamEvents(any(UserMessage.class), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());

        awaitEvent(ChatExecutionTestSupport.stream(chatService, conversation(), 1L, "帮我规划杭州两日游"), "done");

        ArgumentCaptor<UserMessage> delegated = ArgumentCaptor.forClass(UserMessage.class);
        verify(travelMasterAgent).streamEvents(delegated.capture(), any(RuntimeContext.class));
        String text = delegated.getValue().getTextContent();
        assertThat(text).contains("【task_backlog.json】").contains("【intake_result.json】")
                .contains("杭州").contains("T1");
        assertThat(text).doesNotContain("用 read_file 读取");
    }

    // ---------- 同对话二次规划：需求关键字段变化 → 清缓存；旧 review 产物无条件清除（粘滞不跨轮） ----------
    @Test
    void secondPlanningClearsReviewArtifactsAndInvalidatesCache() throws Exception {
        // 上一轮残留：异地 intake（苏州/10-20）+ 旧 review_passed（粘滞顶包源）
        workspace.writeIntakeResult("1", "conv-13", intakeResult("苏州", "2026-10-20"));
        workspace.writeReviewPassed("1", "conv-13",
                new com.travelscope.collab.ReviewPassed(88, 14, List.of("旧轮通过"), "2026-10-18 09:00:00"));
        TaskResultCache cache = mock(TaskResultCache.class);
        ReflectionTestUtils.setField(chatService, "taskResultCache", cache);
        when(intentCascadeRouter.classify(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IntentResult("PLANNING", "完整行程规划"));
        // 本轮 workflow 落定新 intake（杭州/10-24）
        when(requirementWorkflowService.handle(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            workspace.writeIntakeResult("1", "conv-13", intakeResult());
            return CollectOutcome.collected();
        });
        when(taskBacklogPlanner.plan(eq("1"), any(IntakeResult.class))).thenReturn(backlog());
        when(travelMasterAgent.streamEvents(any(UserMessage.class), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());
        workspace.writeItineraryDraft("1", "conv-13", draftForTest());

        awaitEvent(ChatExecutionTestSupport.stream(chatService, conversation(), 1L, "帮我规划杭州两日游"), "done");

        // 结果缓存按关键字段变化全清
        verify(cache).invalidate("1", "conv-13", null);
        // 旧 review_passed 在编排起点被清除（ruleReviewService 未注入 → 本轮不重写，文件应不存在）
        assertThat(workspace.readReviewPassed("1", "conv-13")).isEmpty();
        // 委派文本内联的是新 intake（含杭州、不含苏州）
        ArgumentCaptor<UserMessage> delegated = ArgumentCaptor.forClass(UserMessage.class);
        verify(travelMasterAgent).streamEvents(delegated.capture(), any(RuntimeContext.class));
        assertThat(delegated.getValue().getTextContent()).contains("杭州").doesNotContain("苏州");
    }

    // ==================== 测试数据与 SSE 捕获 ====================

    @Test
    void observedPlanningKeepsWorkflowAndPassesContextToPlanner() throws Exception {
        seedIntakeAndArtifacts(true);
        ObservationContext observation = observeTurns();
        when(intentCascadeRouter.classify(anyString(), anyString(), anyString(), anyString(), sameObservation(observation)))
                .thenReturn(new IntentResult("PLANNING", "行程规划"));
        when(requirementWorkflowService.handle(anyString(), anyString(), anyString(), sameObservation(observation)))
                .thenReturn(CollectOutcome.collected());
        when(taskBacklogPlanner.plan(eq("1"), any(IntakeResult.class))).thenReturn(backlog());
        when(travelMasterAgent.streamEvents(any(UserMessage.class), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());

        List<String> items = awaitEvent(ChatExecutionTestSupport.stream(chatService, conversation(), 1L, "帮我规划杭州两日游"), "done");

        assertThat(payloadOf(items, "done")).contains("杭州两日游完整方案");
        ArgumentCaptor<RuntimeContext> runtime = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(travelMasterAgent).streamEvents(any(UserMessage.class), runtime.capture());
        assertThat(((ObservationContext) runtime.getValue().get(ObservationContext.KEY)).withCancellation(null)).isEqualTo(observation);
        assertThat((Object) runtime.getValue().get("assessment.toolMode")).isEqualTo("LIVE");
        verify(requirementWorkflowService).handle(eq("帮我规划杭州两日游"), eq("1"), eq("conv-13"), sameObservation(observation));
    }

    @Test
    void observedFormSubmissionStillBypassesIntentClassifier() throws Exception {
        ObservationContext observation = observeTurns();
        String message = "{\"__form__\":{\"__action__\":\"cancel\"}}";
        when(requirementWorkflowService.handle(eq(message), eq("1"), eq("conv-13"), sameObservation(observation)))
                .thenReturn(CollectOutcome.cancelled());

        List<String> items = awaitEvent(ChatExecutionTestSupport.stream(chatService, conversation(), 1L, message), "done");

        assertThat(payloadOf(items, "done")).isEqualTo("已取消规划。");
        verifyNoInteractions(intentCascadeRouter, taskBacklogPlanner, travelMasterAgent);
    }

    @Test
    void observedTimeoutKeepsOuterBestEffortRendering() throws Exception {
        seedIntakeAndArtifacts(false);
        ObservationContext observation = observeTurns();
        when(intentCascadeRouter.classify(anyString(), anyString(), anyString(), anyString(), sameObservation(observation)))
                .thenReturn(new IntentResult("PLANNING", "行程规划"));
        when(requirementWorkflowService.handle(anyString(), anyString(), anyString(), sameObservation(observation)))
                .thenReturn(CollectOutcome.collected());
        when(taskBacklogPlanner.plan(eq("1"), any(IntakeResult.class))).thenReturn(backlog());
        when(travelMasterAgent.streamEvents(any(UserMessage.class), any(RuntimeContext.class)))
                .thenReturn(Flux.error(new java.util.concurrent.TimeoutException("synthetic timeout")));

        List<String> items = awaitEvent(ChatExecutionTestSupport.stream(chatService, conversation(), 1L, "规划杭州两日游"), "done");

        assertThat(payloadOf(items, "done")).contains("杭州两日游完整方案").contains("已尽力");
        assertThat(eventNames(items)).doesNotContain("error");
        verify(observationStore).timeout(sameObservation(observation));
    }

    @Test
    @SuppressWarnings("unchecked")
    void assessmentUsesNewWorkflowAndIsolatedPlannerWithoutPersonalSideEffects() throws Exception {
        ObservationContext observation = observeTurns();
        HarnessAgent isolatedMaster = mock(HarnessAgent.class, RETURNS_DEEP_STUBS);
        workspace.writeIntakeResult("13", "conv-13", intakeResult());
        Files.writeString(workspace.getTaskDir("13", "conv-13").resolve(TaskWorkspace.FILE_ITINERARY_DRAFT_MD),
                "# 隔离评测的行程方案");
        when(intentCascadeRouter.classify(anyString(), eq(""), eq("13"), eq("conv-13"), sameObservation(observation)))
                .thenReturn(new IntentResult("PLANNING", "行程规划"));
        when(requirementWorkflowService.handle(anyString(), eq("13"), eq("conv-13"), sameObservation(observation),
                any(com.travelscope.agent.tools.TransportTool.class)))
                .thenReturn(CollectOutcome.collected());
        when(taskBacklogPlanner.plan(eq("13"), any(IntakeResult.class))).thenReturn(backlog());
        when(isolatedMaster.streamEvents(any(UserMessage.class), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());

        String result = chatService.executeAssessment(13L, "规划杭州两日游", observation,
                "FIXTURE", true, "EC-018", isolatedMaster).block(Duration.ofSeconds(5));

        assertThat(result).contains("隔离评测的行程方案");
        ArgumentCaptor<RuntimeContext> runtime = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(isolatedMaster).streamEvents(any(UserMessage.class), runtime.capture());
        assertThat(((ObservationContext) runtime.getValue().get(ObservationContext.KEY)).withCancellation(null)).isEqualTo(observation);
        assertThat((Object) runtime.getValue().get("assessment.toolMode")).isEqualTo("FIXTURE");
        assertThat((Object) runtime.getValue().get("assessment.toolFailure")).isEqualTo(true);
        assertThat((Object) runtime.getValue().get("assessment.fixtureCase")).isEqualTo("EC-018");
        verifyNoInteractions(conversationService, conversationSummaryService, pointsService,
                travelMasterAgent);
    }

    @Test
    void cancellingRequirementExtractionNeverStartsPlanningOrMasterFallback() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        var disconnected = new java.util.concurrent.CountDownLatch(1);
        var ended = new java.util.concurrent.CountDownLatch(1);
        when(intentCascadeRouter.classify(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IntentResult("PLANNING", "test"));
        when(requirementWorkflowService.handle(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            return com.travelscope.common.TurnCancellation.await(
                    reactor.core.publisher.Mono.<CollectOutcome>never().doOnSubscribe(s -> started.countDown()).doOnCancel(disconnected::countDown),
                    java.time.Duration.ofSeconds(5), null);
        });
        var execution = chatService.execution(conversation(), 1L, "plan a trip", null, UUID.randomUUID(), cost -> true);
        var subscription = execution.events.doFinally(signal -> ended.countDown()).subscribe(event -> {}, error -> {});
        try {
            assertThat(started.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            execution.cancel();
            assertThat(disconnected.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(ended.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            verifyNoInteractions(taskBacklogPlanner, travelMasterAgent);
        } finally { subscription.dispose(); }
    }

    private static ObservationContext sameObservation(ObservationContext expected) {
        return org.mockito.ArgumentMatchers.argThat(actual -> actual != null && actual.withCancellation(null).equals(expected));
    }

    private ObservationContext observeTurns() {
        observationStore = mock(ObservationStore.class);
        ObservationContext observation = new ObservationContext(UUID.randomUUID(), UUID.randomUUID(),
                "synthetic-trace", null, null);
        when(observationStore.enabled()).thenReturn(true);
        when(observationStore.begin(anyString(), any(), any())).thenReturn(observation);
        when(observationStore.begin(anyString(), any(), any(), any(UUID.class))).thenReturn(observation);
        when(observationStore.begin(anyString(), any(), any(), any(UUID.class), any())).thenReturn(observation);
        ReflectionTestUtils.setField(chatService, "observationStore", observationStore);
        return observation;
    }

    private static Conversation conversation() {
        Conversation c = new Conversation();
        c.setId(13L);
        return c;
    }

    /** 预置协作产物：intake_result.json + itinerary_draft.json；评审产物由 stubRuleReview 在编排中写实 */
    private void seedIntakeAndArtifacts(boolean passed) throws Exception {
        workspace.writeIntakeResult("1", "conv-13", intakeResult());
        workspace.writeItineraryDraft("1", "conv-13", draftForTest());
        stubRuleReview(passed);
    }

    /** 规则质检打桩：step5 编排调用时把评审产物写实（对齐真实链路：规则服务写产物、渲染层读产物） */
    private void stubRuleReview(boolean passed) {
        RuleReviewService stub = mock(RuleReviewService.class);
        when(stub.check(anyString(), anyString())).thenAnswer(inv -> {
            String u = inv.getArgument(0);
            String s = inv.getArgument(1);
            if (passed) {
                workspace.writeReviewPassed(u, s,
                        new com.travelscope.collab.ReviewPassed(91, 15, List.of("核验一致"), "2026-10-18 12:00:00"));
                return new RuleReviewService.Outcome(true, List.of(), null);
            }
            workspace.writeReviewReport(u, s, new com.travelscope.collab.ReviewReport(
                    List.of(
                            new com.travelscope.collab.ReviewReport.ReviewSegment(1, true, List.of()),
                            new com.travelscope.collab.ReviewReport.ReviewSegment(2, false, List.of("第二天时间过密")),
                            new com.travelscope.collab.ReviewReport.ReviewSegment(3, true, List.of())),
                    new com.travelscope.collab.ReviewReport.ReviewScores(15, 14, 13, 15, 15),
                    72, java.util.Map.of(), "2026-10-18 12:00:00"));
            return new RuleReviewService.Outcome(false, List.of("第二天时间过密"), null);
        });
        ReflectionTestUtils.setField(chatService, "ruleReviewService", stub);
    }

    private static com.travelscope.collab.ItineraryDraft draftForTest() {
        return new com.travelscope.collab.ItineraryDraft(List.of(
                new com.travelscope.collab.ItineraryDraft.DraftDay(1, "2026-10-24", "西湖环线",
                        List.of(new com.travelscope.collab.ItineraryDraft.DraftItem(
                                "09:00-11:30", "西湖环湖", "西湖", "免费", null))),
                new com.travelscope.collab.ItineraryDraft.DraftDay(2, "2026-10-25", "灵隐禅行",
                        List.of(new com.travelscope.collab.ItineraryDraft.DraftItem(
                                "09:00-12:00", "灵隐寺", "灵隐寺", "¥30", null)))),
                null, List.of(), List.of(), "# 杭州两日游完整方案\nDay1 西湖环湖，Day2 灵隐寺。");
    }

    private static IntakeResult intakeResult() {
        return intakeResult("杭州", "2026-10-24");
    }

    private static IntakeResult intakeResult(String destination, String startDate) {
        return new IntakeResult("conv-13", IntakeResult.Status.DONE, 0,
                new IntakeResult.RequirementFields(
                        fv(destination), fv("2"), fv(startDate), fv("上海"),
                        fv(null), fv(null), fv(null), fv(null)),
                false, "2026-10-18 10:00:00");
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
                "2026-10-18 10:00:00");
    }

    /** 读取 SSE 已发全部条目（无 MVC handler 时 send 缓冲在 earlySendAttempts 字段） */
    @SuppressWarnings("unchecked")
    private static List<String> sentItems(SseEmitter emitter) throws Exception {
        Field field = ResponseBodyEmitter.class.getDeclaredField("earlySendAttempts");
        field.setAccessible(true);
        // send 与本读取都在 emitter 监视器下互斥，拿到一致快照
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

    /**
     * 事件名序列。SseEventBuilder 的编码为 "event:{name}\ndata:" 单条目（名字与 data:
     * 前缀同条目，载荷是下一条目）——取 "event:" 后至首个 \n 前的段作为事件名。
     */
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
    @Test void englishPlanningPropagatesLocaleAndInstructionToPlanner() throws Exception {
        seedIntakeAndArtifacts(true);
        when(intentCascadeRouter.classify(anyString(),anyString(),anyString(),anyString())).thenReturn(new IntentResult("PLANNING","plan"));
        when(requirementWorkflowService.handle(anyString(),anyString(),anyString())).thenReturn(CollectOutcome.collected());
        when(taskBacklogPlanner.plan(eq("1"),any(IntakeResult.class))).thenReturn(backlog());
        when(travelMasterAgent.streamEvents(any(UserMessage.class),any(RuntimeContext.class))).thenReturn(Flux.empty());
        var execution=chatService.execution(conversation(),1L,"Plan a trip",null,UUID.randomUUID(),"en",cost->true);
        var events=execution.events.collectList().block(Duration.ofSeconds(5));
        ArgumentCaptor<RuntimeContext> context=ArgumentCaptor.forClass(RuntimeContext.class);
        ArgumentCaptor<UserMessage> input=ArgumentCaptor.forClass(UserMessage.class);
        verify(travelMasterAgent).streamEvents(input.capture(),context.capture());
        assertThat((Object)context.getValue().get(com.travelscope.common.ReplyLanguage.KEY)).isEqualTo("en");
        assertThat(input.getValue().getTextContent()).contains("Use English");
        assertThat(events.stream().filter(e->"itinerary_day".equals(e.getType())).map(e->e.getPayload()).toList()).allMatch(body->body.contains("Time | Activity"));
    }

}
