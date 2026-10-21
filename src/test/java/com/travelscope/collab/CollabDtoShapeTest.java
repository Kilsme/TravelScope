package com.travelscope.collab;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S0.1 验证：9 个工作区契约 DTO 的形状约束。
 * <ul>
 *   <li>均为 record，首组件为 int schemaVersion，且 SCHEMA_VERSION 常量 = 4</li>
 *   <li>便捷构造器（省略 schemaVersion）造出的实例 schemaVersion()==4</li>
 *   <li>fastjson2 序列化含 "schemaVersion":4，反序列化往返相等</li>
 *   <li>旧版本（schemaVersion≠4）的 JSON 反序列化被拒绝（供 S0.2 校验层呈现）</li>
 * </ul>
 */
class CollabDtoShapeTest {

    private static final List<Class<?>> CONTRACT_DTOS = List.of(
            IntakeResult.class, TaskBacklog.class, PoiShortlist.class,
            RoutePlan.class, ExecutionResult.class, ItineraryDraft.class,
            ReviewReport.class, ReviewPassed.class, SessionMeta.class);

    @Test
    void allNineDtosAreRecordsWithLeadingIntSchemaVersion() throws Exception {
        assertThat(CONTRACT_DTOS).hasSize(9);
        for (Class<?> dto : CONTRACT_DTOS) {
            assertThat(dto.isRecord()).as(dto.getSimpleName() + " 应为 record").isTrue();

            RecordComponent[] components = dto.getRecordComponents();
            assertThat(components).as(dto.getSimpleName() + " 应有组件").isNotEmpty();
            assertThat(components[0].getName())
                    .as(dto.getSimpleName() + " 首组件应为 schemaVersion")
                    .isEqualTo("schemaVersion");
            assertThat(components[0].getType())
                    .as(dto.getSimpleName() + " schemaVersion 应为 int")
                    .isEqualTo(int.class);

            var constant = dto.getDeclaredField("SCHEMA_VERSION");
            assertThat(Modifier.isStatic(constant.getModifiers()))
                    .as(dto.getSimpleName() + " SCHEMA_VERSION 应为 static").isTrue();
            assertThat(constant.getInt(null))
                    .as(dto.getSimpleName() + " SCHEMA_VERSION 应为 4").isEqualTo(4);
        }
    }

    @Test
    void convenienceConstructorsPinSchemaVersionToFour() throws Exception {
        List<Object> instances = List.of(
                new IntakeResult("conv-1", IntakeResult.Status.DONE, 1,
                        new IntakeResult.RequirementFields(
                                new IntakeResult.FieldValue("杭州", true),
                                new IntakeResult.FieldValue("2", true),
                                new IntakeResult.FieldValue("2026-10-25", true),
                                new IntakeResult.FieldValue("上海", true),
                                null, null, null, null),
                        false, "2026-10-15T21:30:00"),
                new TaskBacklog("conv-1", List.of(
                        new TaskBacklog.TaskItem("T1", "查询上海到杭州火车票", "train-ticket-query",
                                "P0", "PENDING", "2026-10-15T21:30:00"))),
                new PoiShortlist("杭州", "5 变体并行，去重后 8 个候选", List.of(
                        new PoiShortlist.PoiItem("西湖", 120.15, 30.24, 4.8, "全天", 180,
                                "RAG(chunk=c1)", "地标必去"))),
                new RoutePlan(List.of(
                        new RoutePlan.RouteDay(1, "2026-10-25", List.of(
                                new RoutePlan.RoutePoi("西湖", 120.15, 30.24,
                                        "09:00", "12:00", null, null)), 45)),
                        new RoutePlan.SolverMeta("NN+2opt", "amap-transit", 12, List.of())),
                new ExecutionResult("conv-1", List.of(
                        new ExecutionResult.TaskResultItem("T1", "DONE", "G7331 08:00 上海虹桥-杭州东",
                                List.of("mcp__c12306: 二等座 73 元"), "2026-10-15T21:31:00"))),
                new ItineraryDraft(
                        List.of(new ItineraryDraft.DraftDay(1, "2026-10-25", "西湖环线", List.of(
                                new ItineraryDraft.DraftItem("09:00-12:00", "环湖漫步", "西湖", null, null)))),
                        new ItineraryDraft.Budget("CNY", "约 750 元/人", List.of()),
                        List.of("周末西湖人多，尽早出发"), List.of("返程车次待确认"),
                        "# 杭州 2 日行程草案\n\n（正文略）"),
                new ReviewReport(
                        List.of(new ReviewReport.ReviewSegment(1, false, List.of("宋城开放时间与排期冲突"))),
                        new ReviewReport.ReviewScores(18, 17, 15, 18, 17),
                        85, Map.of("1", List.of("将宋城替换为全天开放景点")), "2026-10-15T21:40:00"),
                new ReviewPassed(85, 15, List.of("调 getTransitRoute 重算通勤核验通过"),
                        "2026-10-15T21:40:00"),
                new SessionMeta("u1", "conv-1", "2026-10-15T21:00:00", "进行中",
                        List.of("travel-master", "itinerary-agent", "reviewer-agent"), null));

        for (Object dto : instances) {
            Method accessor = dto.getClass().getMethod("schemaVersion");
            assertThat(accessor.invoke(dto))
                    .as(dto.getClass().getSimpleName() + " 便捷构造器应固定 schemaVersion=4")
                    .isEqualTo(4);
        }
    }

    @Test
    void jsonRoundTripKeepsSchemaVersionAndContent() {
        RoutePlan route = new RoutePlan(List.of(
                new RoutePlan.RouteDay(1, "2026-10-25", List.of(
                        new RoutePlan.RoutePoi("西湖", 120.15, 30.24, "09:00", "12:00", null, null),
                        new RoutePlan.RoutePoi("灵隐寺", 120.10, 30.24, "13:30", "16:00",
                                "公交", 40)), 85)),
                new RoutePlan.SolverMeta("NN+2opt", "amap-transit", 12,
                        List.of(new RoutePlan.UnplacedPoi("宋城", "COMMUTE_OVERFLOW"))));

        String json = JSON.toJSONString(route);
        assertThat(json).contains("\"schemaVersion\":4");

        RoutePlan back = JSON.parseObject(json, RoutePlan.class);
        assertThat(back).isEqualTo(route);
        assertThat(back.schemaVersion()).isEqualTo(4);

        ReviewReport report = new ReviewReport(
                List.of(new ReviewReport.ReviewSegment(2, false, List.of("第 2 天通勤 100 分钟超限"))),
                new ReviewReport.ReviewScores(18, 16, 11, 18, 17),
                80, Map.of("2", List.of("第 2 天拆出 1 个 POI 到第 3 天")), null);
        ReviewReport reportBack = JSON.parseObject(JSON.toJSONString(report), ReviewReport.class);
        assertThat(reportBack).isEqualTo(report);
    }

    @Test
    void reviewReportMissingR4KeysDefaultsToNormal() {
        // R4 前旧产物（无 deliveryMode / unresolvedIssues 键）→ 归一化 NORMAL + 空清单
        String legacyJson = """
                {"schemaVersion":4,
                 "segments":[{"segment":2,"pass":false,"issues":["第 2 天通勤 100 分钟超限"]}],
                 "scores":{"completeness":18,"feasibility":16,"timeConflict":11,
                           "costBudget":18,"poiReasonableness":17},
                 "total":80,"suggestionsBySegment":{"2":["第 2 天拆出 1 个 POI 到第 3 天"]},
                 "reviewedAt":"2026-10-15T21:40:00"}""";
        ReviewReport report = JSON.parseObject(legacyJson, ReviewReport.class);
        assertThat(report.deliveryMode()).isEqualTo(ReviewReport.DELIVERY_NORMAL);
        assertThat(report.unresolvedIssues()).isEmpty();

        // 非法 deliveryMode 值快速失败（契约防御）
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JSON.parseObject(
                legacyJson.replace("\"total\":80", "\"total\":80,\"deliveryMode\":\"PARTIAL\""),
                ReviewReport.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legacySchemaVersionIsRejected() {
        String legacyJson = """
                {"schemaVersion":3,"sessionId":"conv-1","status":"DONE","clarifyCycles":0,
                 "fields":{},"degraded":false,"collectedAt":"2026-10-15T21:30:00"}
                """;
        // fastjson2 将紧凑构造器的 IllegalArgumentException 包装为 JSONException
        assertThatThrownBy(() -> JSON.parseObject(legacyJson, IntakeResult.class))
                .isInstanceOf(JSONException.class);
    }
}
