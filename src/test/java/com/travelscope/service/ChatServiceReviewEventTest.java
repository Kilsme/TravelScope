package com.travelscope.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.travelscope.agent.IntentCascadeRouter;
import com.travelscope.collab.CollabFileReader;
import com.travelscope.collab.CollabFileWriter;
import com.travelscope.collab.IntakeResult;
import com.travelscope.collab.RoutePlan;
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

    // ---------- 用例 3：R1 PASS_WITH_WARNINGS → 载荷带 warnings，done 标注含提示数 ----------

    @Test
    @DisplayName("R1 软提示交付 → review_report 载荷含 warnings 数组，done 标注「另有 N 项提示」")
    void warningsPayloadEmitted() throws Exception {
        seedPlanningChain();
        com.travelscope.collab.ReviewPassed.ReviewWarning warning =
                new com.travelscope.collab.ReviewPassed.ReviewWarning("BUDGET_DEVIATION",
                        "预算超支：估算 1344 vs 预算上限 1200（偏差 12%）",
                        "偏差在 10%~15% 贴线区间，可按当前预算交付");
        RuleReviewService stub = mock(RuleReviewService.class);
        when(stub.check(anyString(), anyString())).thenAnswer(inv -> {
            // 对齐真实链路：规则质检写带 warnings 的 review_passed，编排层零回炉直接交付
            workspace.writeReviewPassed(inv.getArgument(0), inv.getArgument(1),
                    new com.travelscope.collab.ReviewPassed(88, 16, List.of("核验一致"),
                            List.of(warning), "2026-10-21 12:00:00"));
            return new RuleReviewService.Outcome(RuleReviewService.Outcome.Verdict.PASS_WITH_WARNINGS,
                    List.of("预算超支：估算 1344 vs 预算上限 1200（偏差 12%）"),
                    List.of(warning), null);
        });
        org.springframework.test.util.ReflectionTestUtils.setField(chatService, "ruleReviewService", stub);

        SseEmitter emitter = chatService.streamChat(conversation(), 1L, "帮我规划杭州两日游");
        List<String> items = awaitEvent(emitter, "done");

        assertThat(eventNames(items)).contains("review_report");
        JSONObject payload = JSON.parseObject(payloadOf(items, "review_report"));
        assertThat(payload.getBooleanValue("passed")).isTrue();
        assertThat(payload.getIntValue("total")).isEqualTo(88);
        assertThat(payload.getJSONArray("warnings")).hasSize(1);
        assertThat(payload.getJSONArray("warnings").getJSONObject(0).getString("rule"))
                .isEqualTo("BUDGET_DEVIATION");
        assertThat(payload.getJSONArray("warnings").getJSONObject(0).getString("suggestion"))
                .isNotBlank();
        assertThat(payloadOf(items, "done"))
                .contains("✅ 已通过质量审阅（评分 88/100，另有 1 项提示，详见质检明细）");
    }

    // ---------- 用例 4：R3② 流式行程进度 → 骨架/逐天事件先于 done 到达 ----------

    @Test
    @DisplayName("R3② 流式进度：itinerary_skeleton/itinerary_day 事件先于 done，载荷契约正确")
    void itineraryProgressEventsEmittedBeforeDone() throws Exception {
        seedPlanningChain();
        // 产物：route_plan（骨架源）+ 带「## Day N」节标题的草案（逐天源）
        workspace.writeRoutePlan("1", "conv-13", new RoutePlan(List.of(
                new RoutePlan.RouteDay(1, null, List.of(
                        new RoutePlan.RoutePoi("西湖", 120.15, 30.24, "09:00", "11:00", null, null)), 0)),
                new RoutePlan.SolverMeta("NN+2opt", "amap-transit", 5, List.of())));
        workspace.writeItineraryDraft("1", "conv-13", new com.travelscope.collab.ItineraryDraft(
                List.of(new com.travelscope.collab.ItineraryDraft.DraftDay(1, "2026-10-24", "西湖环线",
                        List.of(new com.travelscope.collab.ItineraryDraft.DraftItem(
                                "09:00-11:30", "环湖漫步", "西湖", "免费", null)))),
                null, List.of(), List.of(),
                "# 杭州两日游完整方案\n\n## Day 1（2026-10-24）西湖环线\n\n| 时间 | 活动 |\n| --- | --- |\n| 09:00 | 环湖 |\n"));

        SseEmitter emitter = chatService.streamChat(conversation(), 1L, "帮我规划杭州两日游");
        List<String> items = awaitEvent(emitter, "done");

        List<String> names = eventNames(items);
        assertThat(names).contains("itinerary_skeleton", "itinerary_day");
        // 顺序契约：进度事件先于 done（done 全文为终稿，前端预览→终稿切换）
        assertThat(names.indexOf("itinerary_skeleton")).isLessThan(names.indexOf("done"));
        assertThat(names.indexOf("itinerary_day")).isLessThan(names.indexOf("done"));
        // 载荷契约
        JSONObject skeleton = JSON.parseObject(payloadOf(items, "itinerary_skeleton"));
        assertThat(skeleton.getIntValue("dayCount")).isEqualTo(1);
        assertThat(skeleton.getJSONArray("days").getJSONObject(0).getJSONArray("poiNames"))
                .containsExactly("西湖");
        JSONObject day = JSON.parseObject(payloadOf(items, "itinerary_day"));
        assertThat(day.getIntValue("day")).isEqualTo(1);
        assertThat(day.getIntValue("version")).isEqualTo(1);
        assertThat(day.getString("markdown")).startsWith("## Day 1");
    }

    // ---------- 用例 5：R4 降级交付 → 契约标记 DEGRADED + 未解决清单随事件与标注下发 ----------

    @Test
    @DisplayName("R4 终检 FAIL → review_report.json 重写 DEGRADED+未解决清单，载荷/标注同源")
    void degradedDeliveryMarkedAndEmitted() throws Exception {
        seedPlanningChain();
        // 规则质检打桩：终检仍 FAIL（reviewer 修订上限 1 已耗尽或未收敛），写 NORMAL 态 FAIL 报告
        // ——编排层 markDegradedDeliveryIfNeeded 应重写为 DEGRADED 并汇总失败段问题
        RuleReviewService stub = mock(RuleReviewService.class);
        when(stub.check(anyString(), anyString())).thenAnswer(inv -> {
            workspace.writeReviewReport(inv.getArgument(0), inv.getArgument(1),
                    new com.travelscope.collab.ReviewReport(
                            List.of(new com.travelscope.collab.ReviewReport.ReviewSegment(1, true, List.of()),
                                    new com.travelscope.collab.ReviewReport.ReviewSegment(2, false,
                                            List.of("Day1 第2项「灵隐寺」开始(11:30) 早于上一项「西湖游船」结束(12:00)")),
                                    new com.travelscope.collab.ReviewReport.ReviewSegment(3, false,
                                            List.of("预算超支：估算 1500 vs 预算上限 1200（偏差 25%）"))),
                            new com.travelscope.collab.ReviewReport.ReviewScores(18, 14, 10, 12, 16),
                            70, java.util.Map.of("2", List.of("调整 Day1 顺序"), "3", List.of("换经济酒店")),
                            "2026-10-21 12:00:00"));
            return new RuleReviewService.Outcome(RuleReviewService.Outcome.Verdict.FAIL,
                    List.of("时间线重叠", "预算超支"), List.of(), "修订任务（测试桩不会消费）");
        });
        org.springframework.test.util.ReflectionTestUtils.setField(chatService, "ruleReviewService", stub);

        SseEmitter emitter = chatService.streamChat(conversation(), 1L, "帮我规划杭州两日游");
        List<String> items = awaitEvent(emitter, "done");

        // ① 契约标记落盘：deliveryMode=DEGRADED + 未解决清单=两失败段 issues 汇总
        com.travelscope.collab.ReviewReport patched =
                workspace.readReviewReport("1", "conv-13").orElseThrow();
        assertThat(patched.deliveryMode())
                .isEqualTo(com.travelscope.collab.ReviewReport.DELIVERY_DEGRADED);
        assertThat(patched.unresolvedIssues()).hasSize(2)
                .anySatisfy(i -> assertThat(i).contains("早于上一项"))
                .anySatisfy(i -> assertThat(i).contains("预算超支"));
        // ② review_report 事件载荷同源（前端可展开清单的数据源）
        JSONObject payload = JSON.parseObject(payloadOf(items, "review_report"));
        assertThat(payload.getBooleanValue("passed")).isFalse();
        assertThat(payload.getString("deliveryMode")).isEqualTo("DEGRADED");
        assertThat(payload.getJSONArray("issues")).hasSize(2);
        // ③ done 标注：一句话升级为「评分 + 未解决问题数」指针
        assertThat(payloadOf(items, "done"))
                .contains("⚠️ 当前最佳版本（已尽力，评分 70/100，2 项未解决问题，详见质检明细）");
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
