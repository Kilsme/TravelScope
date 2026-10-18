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
    private CollabTools tools;
    private RuntimeContext ctx;

    @BeforeEach
    void setUp() {
        workspace = new TaskWorkspace(new CollabFileWriter(tempDir.toString()), new CollabFileReader());
        routePlanner = mock(RoutePlannerServiceImpl.class);
        tools = new CollabTools(mock(PoiRetrievalService.class), routePlanner, workspace,
                new CollabFileWriter(tempDir.toString()));
        ctx = RuntimeContext.builder().userId("u1").sessionId("conv-1").build();
    }

    // ---------- 用例 1：写 poi_shortlist.json（schemaVersion 缺省自动补 4）→ 落盘可读回 ----------
    @Test
    void writeCollabFilePoiShortlistWrittenAndReadable() {
        String json = "{\"destination\":\"杭州\",\"retrievalNote\":\"候选池 8 个，筛选 2 个\","
                + "\"pois\":[{\"name\":\"西湖\",\"source\":\"RAG\",\"rating\":4.8,"
                + "\"reason\":\"必去地标\"},{\"name\":\"灵隐寺\",\"source\":\"RAG+ES\","
                + "\"durationMin\":120,\"reason\":\"文化偏好\"}]}";

        String result = tools.write_collab_file("conv-1", "poi_shortlist.json", json, ctx);

        assertThat(result).contains("已写入 tasks/conv-1/poi_shortlist.json");
        Optional<PoiShortlist> back = workspace.readPoiShortlist("u1", "conv-1");
        assertThat(back).isPresent();
        assertThat(back.get().schemaVersion()).isEqualTo(4);
        assertThat(back.get().destination()).isEqualTo("杭州");
        assertThat(back.get().pois()).hasSize(2);
        assertThat(back.get().pois().get(1).reason()).isEqualTo("文化偏好");
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
        tools.write_collab_file("conv-1", "poi_shortlist.json",
                "{\"destination\":\"杭州\",\"pois\":[{\"name\":\"西湖\",\"source\":\"RAG\"}]}", ctx);
        RoutePlan stub = new RoutePlan(List.of(new RoutePlan.RouteDay(1, null, List.of(), 30)),
                new RoutePlan.SolverMeta("NN+2opt", "amap-transit", 5, List.of()));
        when(routePlanner.planAndWrite(eq("u1"), eq("conv-1"), anyList(), any(), eq("杭州")))
                .thenReturn(stub);

        String result = tools.plan_route("conv-1", null, null, ctx);

        assertThat(result).contains("NN+2opt").contains("amap-transit");
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
}
