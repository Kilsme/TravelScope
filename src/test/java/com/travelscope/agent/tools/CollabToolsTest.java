package com.travelscope.agent.tools;

import com.travelscope.collab.CollabFileReader;
import com.travelscope.collab.CollabFileWriter;
import com.travelscope.collab.IntakeResult;
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

    // ==================== plan_route 无 unplaced 自动链 ====================

    @Test
    void planRouteAutoChainsAssemblyWhenNoUnplaced() {
        workspace.writeIntakeResult("u1", "conv-1", intake());
        seedShortlist();
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

        // 无 unplaced → 自动链触发；组装内部 LLM 在单测中是 mock（产不出有效结构）→ 优雅降级：
        // 成功级结果内嵌的失败原因必须剥掉 ERROR 前缀（防下游误判失败），并提示手动组装。
        // （自动链成功路径由真实链路验证；中间件侧的 ITINERARY 登记见 BookkeepingMiddlewareTest）
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
}
