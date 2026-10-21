package com.travelscope.agent.tools;

import com.travelscope.collab.CollabFileReader;
import com.travelscope.collab.CollabFileWriter;
import com.travelscope.collab.IntakeResult;
import com.travelscope.collab.ItineraryDraft;
import com.travelscope.collab.PoiShortlist;
import com.travelscope.collab.RoutePlan;
import com.travelscope.collab.TaskWorkspace;
import com.travelscope.service.retrieval.PoiRetrievalService;
import com.travelscope.service.route.RoutePlannerServiceImpl;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CollabTools（S2.6 协作契约三件套）测试：真实 CollabFileWriter + TaskWorkspace
 *（@TempDir 落盘），PoiRetrievalService/RoutePlannerServiceImpl 用 Mockito 桩。
 */
class CollabToolsTest {

    @TempDir
    Path tempDir;

    private TaskWorkspace workspace;
    private RoutePlannerServiceImpl routePlanner;
    private PoiRetrievalService poiRetrievalService;
    private com.travelscope.service.TaskResultCache taskResultCache;
    private io.agentscope.core.model.Model chatModel;
    private com.travelscope.agent.tools.AttractionTool attractionTool;
    private CollabTools tools;
    private RuntimeContext ctx;

    @BeforeEach
    void setUp() {
        workspace = new TaskWorkspace(new CollabFileWriter(tempDir.toString()), new CollabFileReader());
        routePlanner = mock(RoutePlannerServiceImpl.class);
        poiRetrievalService = mock(PoiRetrievalService.class);
        taskResultCache = mock(com.travelscope.service.TaskResultCache.class);
        chatModel = mock(io.agentscope.core.model.Model.class);
        attractionTool = mock(com.travelscope.agent.tools.AttractionTool.class);
        // 默认实时 POI 库查无此地（=真幻觉拒绝）；各用例按需覆盖
        when(attractionTool.searchPois(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn("{\"attractions\":[]}");
        tools = new CollabTools(poiRetrievalService, routePlanner, workspace,
                new CollabFileWriter(tempDir.toString()),
                new com.travelscope.service.ItineraryAssembler(),
                taskResultCache,
                chatModel,
                attractionTool);
        ctx = RuntimeContext.builder().userId("u1").sessionId("conv-1").build();
    }

    // ---------- 用例 1：poi_shortlist.json 手写硬锁 → ERROR 引导走名单制 ----------
    @Test
    void writeCollabFilePoiShortlistRejectedToSelectTool() {
        String json = "{\"destination\":\"杭州\",\"pois\":[{\"name\":\"西湖\",\"source\":\"RAG\"}]}";

        String result = tools.write_collab_file("conv-1", "poi_shortlist.json", json, ctx);

        assertThat(result).startsWith("ERROR").contains("select_poi_shortlist");
        assertThat(workspace.readPoiShortlist("u1", "conv-1")).as("硬锁不落盘").isEmpty();
    }

    // ---------- 用例 2：白名单外文件名 → ERROR 拒写 ----------
    @Test
    void writeCollabFileUnknownFileNameRejected() {
        String result = tools.write_collab_file("conv-1", "task_backlog.json", "{}", ctx);

        assertThat(result).startsWith("ERROR").contains("不支持");
        assertThat(workspace.readTaskBacklog("u1", "conv-1")).as("拒写不落盘").isEmpty();
    }

    // ---------- 用例 3：非法/缺字段 JSON → ERROR，不写盘 ----------
    @Test
    void writeCollabFileInvalidJsonRejected() {
        // 坏 JSON
        assertThat(tools.write_collab_file("conv-1", "poi_shortlist.json", "{bad", ctx))
                .startsWith("ERROR");
        // 合法 JSON 但缺 destination（record 强校验非空）
        assertThat(tools.write_collab_file("conv-1", "poi_shortlist.json",
                "{\"pois\":[]}", ctx)).startsWith("ERROR");
        assertThat(workspace.readPoiShortlist("u1", "conv-1")).as("失败不写盘").isEmpty();
    }

    // ---------- 用例 4：plan_route 缺 poi_shortlist.json → ERROR 引导 ----------
    @Test
    void planRouteMissingShortlistGuided() {
        workspace.writeIntakeResult("u1", "conv-1", intake());

        String result = tools.plan_route("conv-1", null, null, ctx);

        assertThat(result).startsWith("ERROR").contains("poi_shortlist.json");
    }

    // ---------- 用例 5：plan_route 正常链（intake+shortlist 预置）→ 返回 RoutePlan JSON ----------
    @Test
    void planRouteHappyPath() {
        workspace.writeIntakeResult("u1", "conv-1", intake());
        seedShortlist();
        RoutePlan stub = new RoutePlan(List.of(new RoutePlan.RouteDay(1, null, List.of(), 30)),
                new RoutePlan.SolverMeta("NN+2opt", "amap-transit", 5, List.of()));
        when(routePlanner.planAndWrite(eq("u1"), eq("conv-1"), anyList(), any(), eq("杭州")))
                .thenReturn(stub);

        String result = tools.plan_route("conv-1", null, null, ctx);

        assertThat(result).contains("NN+2opt").contains("amap-transit");
    }

    /** 短名单直写（绕开 write_collab_file 硬锁——生产等价路径是 select_poi_shortlist） */
    private void seedShortlist() {
        workspace.writePoiShortlist("u1", "conv-1", new PoiShortlist("杭州", "测试清单",
                List.of(new PoiShortlist.PoiItem("西湖", null, null, null, null, 120, "RAG", null))));
    }

    private static IntakeResult intake() {
        return new IntakeResult("conv-1", IntakeResult.Status.DONE, 0,
                new IntakeResult.RequirementFields(
                        fv("杭州"), fv("2"), fv("2026-10-24"), fv("上海"),
                        fv(null), fv(null), fv(null), fv(null)),
                false, "2026-10-18 10:00:00");
    }

    private static IntakeResult.FieldValue fv(String value) {
        return new IntakeResult.FieldValue(value, value != null, null);
    }

    // ==================== 卡③名单制 select_poi_shortlist ====================

    /** 候选池入缓存（首轮 retrieve 返回的 JSON 全文，记账中间件登记语义） */
    private void seedPoolCache() {
        var pool = new com.travelscope.service.retrieval.dto.PoiCandidatePool(
                List.of(
                        new com.travelscope.service.retrieval.dto.PoiCandidatePool.Candidate(
                                "c1", "西湖 杭州地标 湖光山色 免费开放", "RAG", 0.9, null, List.of("西湖")),
                        new com.travelscope.service.retrieval.dto.PoiCandidatePool.Candidate(
                                "c2", "灵隐寺 千年古刹 文化底蕴", "RAG+ES", 0.8, null, List.of("灵隐寺"))),
                new com.travelscope.service.retrieval.dto.PoiCandidatePool.PoolStats(
                        2, new java.util.LinkedHashMap<>(), 2, 2));
        when(taskResultCache.get("u1", "conv-1",
                com.travelscope.service.TaskResultCache.TaskType.POI))
                .thenReturn(com.alibaba.fastjson2.JSON.toJSONString(pool));
    }

    /** searchPois 桩：给指定名字的景点返回带硬字段的实时库结果 */
    private void stubSearchPois(String json) {
        when(attractionTool.searchPois(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(json);
    }

    @Test
    void selectShortlistHappyPath() {
        workspace.writeIntakeResult("u1", "conv-1", intake());
        seedPoolCache(); // 池内命中 → source 继承候选池标记
        stubSearchPois("{\"attractions\":[{\"name\":\"西湖\",\"location\":\"120.15,30.24\","
                + "\"rating\":4.8,\"openHours\":\"全天\",\"source\":\"高德\"},"
                + "{\"name\":\"灵隐寺\",\"location\":\"120.10,30.24\",\"rating\":4.7,"
                + "\"openHours\":\"07:00-18:00\",\"source\":\"高德\"}]}");

        String result = tools.select_poi_shortlist("conv-1", "西湖，灵隐寺", "150,90",
                "必去地标；文化偏好", ctx);

        assertThat(result).startsWith("POI 筛选完成").contains("已落盘");
        var back = workspace.readPoiShortlist("u1", "conv-1");
        assertThat(back).isPresent();
        assertThat(back.get().pois()).hasSize(2);
        assertThat(back.get().pois().get(0).name()).isEqualTo("西湖");
        assertThat(back.get().pois().get(0).durationMin()).isEqualTo(150);
        // 硬字段由实时库补齐（lng 不再为空）；source 池内命中继承 RAG
        assertThat(back.get().pois().get(0).lng()).isEqualTo(120.15);
        assertThat(back.get().pois().get(0).source()).isEqualTo("RAG");
        assertThat(back.get().pois().get(1).reason()).isEqualTo("文化偏好");
        assertThat(back.get().pois().get(1).openHours()).isEqualTo("07:00-18:00");
    }

    @Test
    void selectShortlistRejectsOutOfPool() {
        workspace.writeIntakeResult("u1", "conv-1", intake());
        seedPoolCache();
        // 默认桩：实时库查无此地 → 真幻觉拒绝

        String result = tools.select_poi_shortlist("conv-1", "西湖，幽灵古镇", null, null, ctx);

        assertThat(result).startsWith("ERROR").contains("幽灵古镇").contains("查无此地");
        assertThat(workspace.readPoiShortlist("u1", "conv-1")).as("幻觉名整体拒绝不落盘").isEmpty();
    }

    @Test
    void selectShortlistMissingPoolGuided() {
        workspace.writeIntakeResult("u1", "conv-1", intake());
        // 不喂候选池缓存——池只是 source 继承源，不再是硬门；searchPois 实证存在即可
        stubSearchPois("{\"attractions\":[{\"name\":\"西湖\",\"location\":\"120.15,30.24\","
                + "\"rating\":4.8,\"source\":\"高德\"}]}");

        String result = tools.select_poi_shortlist("conv-1", "西湖", null, null, ctx);

        assertThat(result).startsWith("POI 筛选完成");
        assertThat(workspace.readPoiShortlist("u1", "conv-1")).isPresent();
    }

    @Test
    void selectShortlistFallsBackToFreshPoolWhenCachePolluted() {
        workspace.writeIntakeResult("u1", "conv-1", intake());
        seedPoolCache(); // 缓存池被污染（只有杭州 POI）——池内不命中只要实时库能实证就放行
        stubSearchPois("{\"attractions\":[{\"name\":\"拙政园\",\"location\":\"120.629,31.324\","
                + "\"rating\":4.6,\"openHours\":\"07:30-17:30\",\"source\":\"飞猪\"}]}");

        String result = tools.select_poi_shortlist("conv-1", "拙政园", "120", "园林首选", ctx);

        assertThat(result).as("缓存池污染/池薄时 searchPois 实证放行").startsWith("POI 筛选完成");
        var back = workspace.readPoiShortlist("u1", "conv-1");
        assertThat(back).isPresent();
        // 池外实证名：source 用实时库来源（飞猪），硬字段齐全
        assertThat(back.get().pois().get(0).source()).isEqualTo("飞猪");
        assertThat(back.get().pois().get(0).lng()).isEqualTo(120.629);
    }

    // ---------- L1a 名单实证并行化：保序 + 并发真实发生 + 抖动重试 ----------

    @Test
    void selectShortlistResolvesInParallelPreservingOrder() {
        workspace.writeIntakeResult("u1", "conv-1", intake());
        java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maxInFlight = new java.util.concurrent.atomic.AtomicInteger();
        when(attractionTool.searchPois(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv -> {
            String keyword = inv.getArgument(1);
            int cur = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(cur, Math::max);
            try {
                Thread.sleep(300); // 模拟 IO（flyai CLI 子进程 + 高德 HTTP）
            } finally {
                inFlight.decrementAndGet();
            }
            return "{\"attractions\":[{\"name\":\"" + keyword + "\",\"location\":\"120.15,30.24\","
                    + "\"rating\":4.8,\"source\":\"高德\"}]}";
        });

        String result = tools.select_poi_shortlist("conv-1", "甲,乙,丙,丁", null, null, ctx);

        assertThat(result).startsWith("POI 筛选完成");
        assertThat(maxInFlight.get()).as("L1a 实证并行真实发生（串行恒为 1）").isGreaterThan(1);
        var back = workspace.readPoiShortlist("u1", "conv-1").orElseThrow();
        assertThat(back.pois()).extracting(com.travelscope.collab.PoiShortlist.PoiItem::name)
                .as("并行解析按提交序 join 保序").containsExactly("甲", "乙", "丙", "丁");
    }

    @Test
    void selectShortlistRetriesTransientFailure() {
        workspace.writeIntakeResult("u1", "conv-1", intake());
        // 「灵隐寺」首调抛 QPS 类异常（resolvePoiItem 捕获→null），600ms 后整单重试成功
        when(attractionTool.searchPois(anyString(), eq("灵隐寺"), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new RuntimeException("CUQPS_HAS_EXCEEDED_THE_LIMIT"))
                .thenReturn("{\"attractions\":[{\"name\":\"灵隐寺\",\"location\":\"120.10,30.24\","
                        + "\"rating\":4.7,\"source\":\"高德\"}]}");
        when(attractionTool.searchPois(anyString(), eq("西湖"), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn("{\"attractions\":[{\"name\":\"西湖\",\"location\":\"120.15,30.24\","
                        + "\"rating\":4.8,\"source\":\"高德\"}]}");

        String result = tools.select_poi_shortlist("conv-1", "西湖,灵隐寺", null, null, ctx);

        assertThat(result).as("瞬时 QPS 抖动经重试吸收，不误杀名单").startsWith("POI 筛选完成");
        org.mockito.Mockito.verify(attractionTool, org.mockito.Mockito.times(2))
                .searchPois(anyString(), eq("灵隐寺"), org.mockito.ArgumentMatchers.anyInt());
    }

    // ---------- L1c 收尾轮消除：自动链成功文本附终止指令 ----------

    @Test
    void autoChainSuccessTextContainsTerminationInstruction() {
        RoutePlan plan = new RoutePlan(List.of(
                new RoutePlan.RouteDay(1, null, List.of(
                        new RoutePlan.RoutePoi("西湖", 120.15, 30.24, "09:00", "11:00", null, null)), 0),
                new RoutePlan.RouteDay(2, null, List.of(
                        new RoutePlan.RoutePoi("灵隐寺", 120.10, 30.24, "09:00", "12:00", null, null),
                        new RoutePlan.RoutePoi("雷峰塔", 120.14, 30.23, "14:00", "16:00", "公交", 30)), 30)),
                new RoutePlan.SolverMeta("NN+2opt", "amap-transit", 5, List.of()));

        String text = CollabTools.autoChainSuccessText("{\"solverMeta\":{}}", "已组装并写入", plan);

        assertThat(text).contains(CollabTools.AUTO_ASSEMBLED_MARKER)
                .contains("【系统终止指令】")
                .contains("原样回复以下一行")
                .contains("行程草案已生成：2 天 3 景");
    }

    // ---------- L1d shortlist 4×days 硬截断 ----------

    @Test
    void selectShortlistTruncatesToFourPerDay() {
        workspace.writeIntakeResult("u1", "conv-1", intake()); // days=2 → 上限 8
        when(attractionTool.searchPois(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv -> {
            String keyword = inv.getArgument(1);
            return "{\"attractions\":[{\"name\":\"" + keyword + "\",\"location\":\"120.15,30.24\","
                    + "\"rating\":4.8,\"source\":\"高德\"}]}";
        });
        String tenNames = "名1,名2,名3,名4,名5,名6,名7,名8,名9,名10";

        String result = tools.select_poi_shortlist("conv-1", tenNames, null, null, ctx);

        assertThat(result).startsWith("POI 筛选完成")
                .contains("已按 4×days=8 上限截断以下 2 项")
                .contains("名9").contains("名10");
        var back = workspace.readPoiShortlist("u1", "conv-1").orElseThrow();
        assertThat(back.pois()).extracting(com.travelscope.collab.PoiShortlist.PoiItem::name)
                .as("截断保留前 4×days 且保序")
                .containsExactly("名1", "名2", "名3", "名4", "名5", "名6", "名7", "名8");
    }

    @Test
    void selectShortlistWithinCapNotTruncated() {
        workspace.writeIntakeResult("u1", "conv-1", intake()); // days=2 → 上限 8，传 8 个恰好不截
        when(attractionTool.searchPois(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv -> {
            String keyword = inv.getArgument(1);
            return "{\"attractions\":[{\"name\":\"" + keyword + "\",\"location\":\"120.15,30.24\","
                    + "\"rating\":4.8,\"source\":\"高德\"}]}";
        });

        String result = tools.select_poi_shortlist("conv-1", "名1,名2,名3,名4,名5,名6,名7,名8", null, null, ctx);

        assertThat(result).startsWith("POI 筛选完成").doesNotContain("截断");
        assertThat(workspace.readPoiShortlist("u1", "conv-1").orElseThrow().pois()).hasSize(8);
    }

    // ---------- L2① 组装 prompt 紧凑视图：剥渲染无关字段 ----------

    @Test
    void compactViewsStripRenderIrrelevantFields() {
        RoutePlan plan = new RoutePlan(List.of(
                new RoutePlan.RouteDay(1, null, List.of(
                        new RoutePlan.RoutePoi("西湖", 120.15, 30.24, "09:00", "11:00", null, null),
                        new RoutePlan.RoutePoi("灵隐寺", 120.10, 30.24, "13:30", "16:00", "公交", 40)), 40)),
                new RoutePlan.SolverMeta("NN+2opt", "amap-transit", 5, List.of()));

        String view = CollabTools.compactRoutePlanView(plan);

        assertThat(view).contains("西湖").contains("09:00-11:00").contains("commuteMin")
                // 剥 solverMeta/坐标/交通方式/天通勤总计/指纹（渲染无关）
                .doesNotContain("solverMeta").doesNotContain("120.15")
                .doesNotContain("公交").doesNotContain("commuteMinutesTotal")
                .doesNotContain("inputFingerprint");

        String intakeView = CollabTools.compactIntakeView(intake());

        assertThat(intakeView).contains("杭州").contains("2026-10-24").contains("上海")
                // 剥 FieldValue 包装与元信息；空值字段省略
                .doesNotContain("confirmed").doesNotContain("clarifyCycles")
                .doesNotContain("schemaVersion").doesNotContain("budget");
    }

    // ---------- P2 候选池瘦身视图：裁决策无关字段 + content 截断 ----------

    @Test
    void slimViewDropsDecisionIrrelevantFieldsAndTruncates() {
        String longText = "西湖是杭州最著名的景点，".repeat(30); // 300+ 字
        var pool = new com.travelscope.service.retrieval.dto.PoiCandidatePool(
                List.of(new com.travelscope.service.retrieval.dto.PoiCandidatePool.Candidate(
                                "seed-1:1", longText, "RAG", 0.9, 0.88, List.of("西湖")),
                        new com.travelscope.service.retrieval.dto.PoiCandidatePool.Candidate(
                                "hard:飞猪:灵隐寺", "灵隐寺｜评分=4.7", "HARD_COMPLETE", 0, null,
                                List.of("灵隐寺"))),
                new com.travelscope.service.retrieval.dto.PoiCandidatePool.PoolStats(
                        2, new java.util.LinkedHashMap<>(), 2, 1));

        String slim = pool.toSlimView(160);

        assertThat(slim).doesNotContain("chunkId").doesNotContain("vectorScore")
                .doesNotContain("hitVariants").doesNotContain("seed-1:1");
        com.alibaba.fastjson2.JSONObject root = com.alibaba.fastjson2.JSON.parseObject(slim);
        com.alibaba.fastjson2.JSONArray cands = root.getJSONArray("candidates");
        assertThat(cands.getJSONObject(0).getString("content"))
                .as("content 截断 160 字 + …").hasSize(161).endsWith("…");
        assertThat(cands.getJSONObject(0).getString("source")).isEqualTo("RAG");
        assertThat(cands.getJSONObject(1).getString("content"))
                .as("短内容不截断").isEqualTo("灵隐寺｜评分=4.7");
        assertThat(root.getJSONObject("stats").getIntValue("variantCount")).isEqualTo(2);
    }

    // ---------- P3 组装重构：结构代码直出 + 文案并行 + 合并护栏 ----------

    /** 双日路线落盘（Day1 两 POI、Day2 一 POI；shortlist 同名——防幻觉过滤器按名匹配） */
    private void seedTwoDayRoute() {
        workspace.writeRoutePlan("u1", "conv-1", new RoutePlan(List.of(
                new RoutePlan.RouteDay(1, "2026-10-24", List.of(
                        new RoutePlan.RoutePoi("西湖", 120.15, 30.24, "09:00", "11:00", null, null),
                        new RoutePlan.RoutePoi("灵隐寺", 120.10, 30.24, "13:30", "16:00", "公交", 40)), 40),
                new RoutePlan.RouteDay(2, "2026-10-25", List.of(
                        new RoutePlan.RoutePoi("雷峰塔", 120.14, 30.23, "09:00", "11:00", null, null)), 0)),
                new RoutePlan.SolverMeta("NN+2opt", "amap-transit", 5, List.of())));
        workspace.writePoiShortlist("u1", "conv-1", new PoiShortlist("杭州", "测试清单",
                List.of(new PoiShortlist.PoiItem("西湖", null, null, null, null, 120, "RAG", null),
                        new PoiShortlist.PoiItem("灵隐寺", null, null, null, null, 120, "RAG", null),
                        new PoiShortlist.PoiItem("雷峰塔", null, null, null, null, 120, "RAG", null))));
    }

    @SuppressWarnings("unchecked")
    private void stubCopyWriters(Object dayStub, Object globalStub) {
        org.springframework.test.util.ReflectionTestUtils.setField(tools, "dayCopyWriter", dayStub);
        org.springframework.test.util.ReflectionTestUtils.setField(tools, "globalCopyWriter", globalStub);
    }

    @Test
    void p3StructureCodeBuiltAndCopyMerged() {
        workspace.writeIntakeResult("u1", "conv-1", intake());
        seedTwoDayRoute();
        stubCopyWriters(
                (java.util.function.BiFunction<String, String, CollabTools.DayCopy>) (dayView, intakeView) ->
                        dayView.contains("\"day\":1")
                                ? new CollabTools.DayCopy("园林禅意日",
                                        List.of("环湖漫步", "千年古刹"), List.of("免费", "¥30"))
                                : new CollabTools.DayCopy("登塔远眺", List.of("俯瞰西湖"), List.of("¥40")),
                (java.util.function.BiFunction<String, String, CollabTools.GlobalCopy>) (d, i) ->
                        new CollabTools.GlobalCopy(
                                new com.travelscope.collab.ItineraryDraft.Budget("CNY", "约 1000 元",
                                        List.of(new com.travelscope.collab.ItineraryDraft.BudgetItem(
                                                "门票", "约 70", "两人"))),
                                List.of("注意防晒"), List.of("预算为估算待确认")));

        String result = tools.assemble_itinerary("conv-1", ctx);

        assertThat(result).startsWith("已组装并写入");
        var draft = workspace.readItineraryDraft("u1", "conv-1").orElseThrow();
        var day1 = draft.days().get(0);
        assertThat(day1.theme()).isEqualTo("园林禅意日");
        // P3 护栏：time/location/activity 由 route_plan 结构直出，LLM 文案只进 theme/note/cost
        assertThat(day1.items().get(0).time()).isEqualTo("09:00-11:00");
        assertThat(day1.items().get(0).activity()).isEqualTo("西湖");
        assertThat(day1.items().get(0).location()).isEqualTo("西湖");
        assertThat(day1.items().get(0).note()).isEqualTo("环湖漫步");
        assertThat(day1.items().get(0).cost()).isEqualTo("免费");
        assertThat(day1.items().get(1).cost()).isEqualTo("¥30");
        assertThat(draft.days().get(1).theme()).isEqualTo("登塔远眺");
        assertThat(draft.budget().totalEstimate()).isEqualTo("约 1000 元");
        assertThat(draft.notes()).contains("注意防晒");
        assertThat(draft.pendingConfirm()).contains("预算为估算待确认");
        assertThat(draft.markdownBody()).contains("## Day 1").contains("园林禅意日").contains("环湖漫步");
    }

    @Test
    void p3DayCopiesParallelOrderAndDegradation() {
        workspace.writeIntakeResult("u1", "conv-1", intake());
        workspace.writeRoutePlan("u1", "conv-1", new RoutePlan(List.of(
                new RoutePlan.RouteDay(1, "2026-10-24", List.of(
                        new RoutePlan.RoutePoi("西湖", 120.15, 30.24, "09:00", "11:00", null, null)), 0),
                new RoutePlan.RouteDay(2, "2026-10-25", List.of(
                        new RoutePlan.RoutePoi("灵隐寺", 120.10, 30.24, "09:00", "11:00", null, null)), 0),
                new RoutePlan.RouteDay(3, "2026-10-26", List.of(
                        new RoutePlan.RoutePoi("雷峰塔", 120.14, 30.23, "09:00", "11:00", null, null)), 0)),
                new RoutePlan.SolverMeta("NN+2opt", "amap-transit", 5, List.of())));
        workspace.writePoiShortlist("u1", "conv-1", new PoiShortlist("杭州", "测试清单",
                List.of(new PoiShortlist.PoiItem("西湖", null, null, null, null, 120, "RAG", null),
                        new PoiShortlist.PoiItem("灵隐寺", null, null, null, null, 120, "RAG", null),
                        new PoiShortlist.PoiItem("雷峰塔", null, null, null, null, 120, "RAG", null))));
        java.util.concurrent.atomic.AtomicInteger day3Calls = new java.util.concurrent.atomic.AtomicInteger();
        stubCopyWriters(
                (java.util.function.BiFunction<String, String, CollabTools.DayCopy>) (dayView, intakeView) -> {
                    try {
                        Thread.sleep(400); // 模拟文案轮 IO
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (dayView.contains("\"day\":2")) {
                        return null; // 恒失败 → 重试一次仍败 → 该天降级
                    }
                    if (dayView.contains("\"day\":3") && day3Calls.incrementAndGet() == 1) {
                        return null; // 首次失败，重试成功
                    }
                    return new CollabTools.DayCopy("主题三", List.of(), List.of());
                },
                (java.util.function.BiFunction<String, String, CollabTools.GlobalCopy>) (d, i) ->
                        new CollabTools.GlobalCopy(null, List.of(), List.of()));

        long t0 = System.nanoTime();
        String result = tools.assemble_itinerary("conv-1", ctx);
        long wallMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(result).startsWith("已组装并写入");
        // 并行墙钟：day1=400ms / day2=2×400 / day3=2×400 → 并行 ≈800ms；串行 ≥2000ms
        assertThat(wallMs).as("N 路并行墙钟 ≈ 最慢一天（重试后）").isLessThan(1500);
        var draft = workspace.readItineraryDraft("u1", "conv-1").orElseThrow();
        assertThat(draft.days())
                .extracting(com.travelscope.collab.ItineraryDraft.DraftDay::day)
                .containsExactly(1, 2, 3);
        assertThat(draft.days().get(1).theme()).as("day2 降级无主题").isNull();
        assertThat(draft.days().get(1).items().get(0).activity()).as("降级天 activity=POI 名直出")
                .isEqualTo("灵隐寺");
        assertThat(draft.notes()).as("降级注记进 notes").anySatisfy(
                n -> assertThat(n).contains("第 2 天文案生成失败"));
    }

    @Test
    void p3AllDegradedReturnsErrorWithWholeRetry() {
        workspace.writeIntakeResult("u1", "conv-1", intake());
        seedTwoDayRoute();
        stubCopyWriters(
                (java.util.function.BiFunction<String, String, CollabTools.DayCopy>) (d, i) -> null,
                (java.util.function.BiFunction<String, String, CollabTools.GlobalCopy>) (d, i) -> null);

        String result = tools.assemble_itinerary("conv-1", ctx);

        assertThat(result).startsWith("ERROR").contains("草案结构字段生成失败").contains("已自动重试 1 次");
    }

    // ==================== plan_route 无 unplaced 自动链 ====================

    @Test
    void planRouteAutoChainsAssemblyWhenNoUnplaced() {
        workspace.writeIntakeResult("u1", "conv-1", intake());
        seedShortlist();
        // P3 测试接缝：文案/全局段注入空桩（mock Model 走真实 ReActAgent 路径会慢且不可控），
        // 使骨架编排确定性地整体降级 → doAssembleItinerary 返回 ERROR → 自动链走降级分支
        org.springframework.test.util.ReflectionTestUtils.setField(tools, "dayCopyWriter",
                (java.util.function.BiFunction<String, String, CollabTools.DayCopy>) (d, i) -> null);
        org.springframework.test.util.ReflectionTestUtils.setField(tools, "globalCopyWriter",
                (java.util.function.BiFunction<String, String, CollabTools.GlobalCopy>) (d, i) -> null);
        // commuteMinutesTotal=0：桩要经得起 writeRoutePlan 的 Schema 校验（0 ≤ 各段之和(0)+1）
        RoutePlan stub = new RoutePlan(List.of(new RoutePlan.RouteDay(1, null, List.of(), 0)),
                new RoutePlan.SolverMeta("NN+2opt", "amap-transit", 5, List.of()));
        // 真实 planAndWrite 会落盘 route_plan.json（自动链的组装要读它）——桩补同样的副作用
        when(routePlanner.planAndWrite(eq("u1"), eq("conv-1"), anyList(), any(), eq("杭州")))
                .thenAnswer(inv -> {
                    workspace.writeRoutePlan("u1", "conv-1", stub);
                    return stub;
                });

        String result = tools.plan_route("conv-1", null, null, ctx);

        // 无 unplaced → 自动链触发；全部文案降级 → 组装 ERROR → 优雅降级：
        // 成功级结果内嵌的失败原因必须剥掉 ERROR 前缀（防下游误判失败），并提示手动组装。
        assertThat(result).contains("NN+2opt");
        assertThat(result).contains("自动组装未完成").contains("assemble_itinerary");
        assertThat(result).doesNotContain("ERROR");
    }

    @Test
    void planRouteNoChainWhenUnplaced() {
        workspace.writeIntakeResult("u1", "conv-1", intake());
        seedShortlist();
        RoutePlan stub = new RoutePlan(List.of(new RoutePlan.RouteDay(1, null, List.of(), 30)),
                new RoutePlan.SolverMeta("NN+2opt", "amap-transit", 5,
                        List.of(new RoutePlan.UnplacedPoi("虎丘", "COMMUTE_OVERFLOW"))));
        when(routePlanner.planAndWrite(eq("u1"), eq("conv-1"), anyList(), any(), eq("杭州")))
                .thenReturn(stub);

        String result = tools.plan_route("conv-1", null, null, ctx);

        assertThat(result).contains("虎丘")
                .doesNotContain(CollabTools.AUTO_ASSEMBLED_MARKER);
        assertThat(workspace.readItineraryDraft("u1", "conv-1")).as("有 unplaced 不自动组装").isEmpty();
    }

    // ==================== R2 增量重组装：revise 链 + 骨架合并 ====================

    @Test
    void reviseRoutesIncrementalWhenPreviousPlanExists() {
        workspace.writeIntakeResult("u1", "conv-1", intake());
        seedShortlist();
        // 旧产物两件（revise 增量链的输入）：route_plan + itinerary_draft
        // commuteMinutesTotal=0：空 pois 桩要经得起 writeRoutePlan 的 Schema 校验（0 ≤ 各段之和(0)+1）
        RoutePlan previous = new RoutePlan(
                List.of(new RoutePlan.RouteDay(1, null, List.of(), 0)),
                new RoutePlan.SolverMeta("NN+2opt", "amap-transit", 5, List.of()));
        workspace.writeRoutePlan("u1", "conv-1", previous);
        workspace.writeItineraryDraft("u1", "conv-1", new ItineraryDraft(
                List.of(new ItineraryDraft.DraftDay(1, "2026-10-24", "西湖", List.of())),
                null, List.of(), List.of(), "旧草案正文"));
        // 新增 POI 的实时库实证（resolvePoiItem 桩）
        when(attractionTool.searchPois(eq("杭州"), eq("灵隐寺"), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn("{\"attractions\":[{\"name\":\"灵隐寺\",\"location\":\"120.0962,30.2435\","
                        + "\"source\":\"高德\",\"openHours\":\"07:00-18:00\"}]}");
        RoutePlannerServiceImpl.IncrementalOutcome inc = new RoutePlannerServiceImpl.IncrementalOutcome(
                new RoutePlan(List.of(new RoutePlan.RouteDay(1, null, List.of(), 0)),
                        new RoutePlan.SolverMeta("NN+2opt", "amap-transit", 3, List.of())),
                java.util.Set.of(), java.util.Set.of(1));
        when(routePlanner.planAndWriteIncremental(
                eq("u1"), eq("conv-1"), anyList(), any(), eq("杭州"), any(RoutePlan.class)))
                .thenReturn(inc);

        String result = tools.revise_poi_selection("conv-1", "灵隐寺", null, ctx);

        // LLM 骨架（mock 模型）增量/全量两级都失败 → 优雅降级 ERROR 文案
        assertThat(result).startsWith("ERROR").contains("重组装失败");
        // 路线走了增量入口；全量入口未被触碰
        verify(routePlanner).planAndWriteIncremental(
                eq("u1"), eq("conv-1"), anyList(), any(), eq("杭州"), any(RoutePlan.class));
        verify(routePlanner, never()).planAndWrite(anyString(), anyString(), anyList(), any(), anyString());
        // shortlist 已更新（+灵隐寺）
        assertThat(workspace.readPoiShortlist("u1", "conv-1"))
                .hasValueSatisfying(s -> assertThat(s.pois())
                        .extracting(PoiShortlist.PoiItem::name)
                        .contains("西湖", "灵隐寺"));
    }

    @Test
    void reviseFallsBackToFullRouteWithoutPreviousPlan() {
        workspace.writeIntakeResult("u1", "conv-1", intake());
        seedShortlist();
        when(attractionTool.searchPois(eq("杭州"), eq("灵隐寺"), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn("{\"attractions\":[{\"name\":\"灵隐寺\",\"location\":\"120.0962,30.2435\","
                        + "\"source\":\"高德\",\"openHours\":\"07:00-18:00\"}]}");
        RoutePlan stub = new RoutePlan(List.of(new RoutePlan.RouteDay(1, null, List.of(), 0)),
                new RoutePlan.SolverMeta("NN+2opt", "amap-transit", 5, List.of()));
        when(routePlanner.planAndWrite(eq("u1"), eq("conv-1"), anyList(), any(), eq("杭州")))
                .thenAnswer(inv -> {
                    workspace.writeRoutePlan("u1", "conv-1", stub);
                    return stub;
                });

        String result = tools.revise_poi_selection("conv-1", "灵隐寺", null, ctx);

        // 无旧 route_plan → 全量路径（现状行为），增量入口未被触碰
        verify(routePlanner).planAndWrite(eq("u1"), eq("conv-1"), anyList(), any(), eq("杭州"));
        verify(routePlanner, never()).planAndWriteIncremental(
                anyString(), anyString(), anyList(), any(), anyString(), any(RoutePlan.class));
        assertThat(result).startsWith("ERROR").contains("重组装失败");  // mock 模型组装失败照旧降级
    }

    @Test
    void mergeIncrementalSkeletonKeepsUnchangedDaysVerbatim() {
        ItineraryDraft.DraftDay day1 = new ItineraryDraft.DraftDay(1, "2026-10-24", "西湖环线",
                List.of(new ItineraryDraft.DraftItem("09:00-11:30", "环湖漫步", "西湖", "免费", null)));
        ItineraryDraft.DraftDay day2 = new ItineraryDraft.DraftDay(2, "2026-10-25", "旧主题", List.of());
        ItineraryDraft.DraftDay day3 = new ItineraryDraft.DraftDay(3, "2026-10-26", "灵隐祈福", List.of());
        ItineraryDraft oldDraft = new ItineraryDraft(List.of(day1, day2, day3),
                new ItineraryDraft.Budget("CNY", "900", List.of()),
                List.of("旧注意事项"), List.of(), "旧正文");
        ItineraryDraft.DraftDay freshDay2 = new ItineraryDraft.DraftDay(2, "2026-10-25", "新主题",
                List.of(new ItineraryDraft.DraftItem("10:00-12:00", "游览新景点", "新景点", "¥80", null)));
        ItineraryDraft partial = new ItineraryDraft(List.of(freshDay2),
                new ItineraryDraft.Budget("CNY", "1000", List.of()),
                List.of("新注意事项"), List.of("待确认项"), "组装中");

        ItineraryDraft merged = CollabTools.mergeIncrementalSkeleton(
                oldDraft, partial, java.util.Set.of(2));

        assertThat(merged.days()).hasSize(3);
        assertThat(merged.days().get(0)).isEqualTo(day1);       // 未变天逐字沿用（代码级，不信模型）
        assertThat(merged.days().get(1)).isEqualTo(freshDay2);  // 变化天取增量骨架
        assertThat(merged.days().get(2)).isEqualTo(day3);
        assertThat(merged.budget().totalEstimate()).isEqualTo("1000");  // 全局字段取增量骨架
        assertThat(merged.notes()).containsExactly("新注意事项");
        assertThat(merged.pendingConfirm()).containsExactly("待确认项");
    }

    @Test
    void mergeIncrementalSkeletonNullWhenChangedDayMissing() {
        ItineraryDraft oldDraft = new ItineraryDraft(
                List.of(new ItineraryDraft.DraftDay(1, null, "a", List.of()),
                        new ItineraryDraft.DraftDay(2, null, "b", List.of()),
                        new ItineraryDraft.DraftDay(3, null, "c", List.of())),
                null, List.of(), List.of(), "旧正文");
        // 变化天 {2,3}，增量骨架只回了 day2 → 缺 day3 → null（降级全量信号）
        ItineraryDraft partial = new ItineraryDraft(
                List.of(new ItineraryDraft.DraftDay(2, null, "b2", List.of())),
                null, List.of(), List.of(), "组装中");

        ItineraryDraft merged = CollabTools.mergeIncrementalSkeleton(
                oldDraft, partial, java.util.Set.of(2, 3));

        assertThat(merged).as("变化天缺失任一天即不可用（旧草案该天 POI 已变）").isNull();
    }
}
