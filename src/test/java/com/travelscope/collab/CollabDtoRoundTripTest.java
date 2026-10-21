package com.travelscope.collab;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S0.7 验证：9 个契约 DTO 全量三态往返（fastjson2 直接往返 / CollabFileWriter→Reader
 * 落盘往返 / Schema 校验往返），外加 fixture（resources/collab/*.json）与代码构造的
 * 一致性锚——fixture 即契约的可读样例文档。
 * <p>
 * fixture 内容参考真实工作区（conv-97，杭州 2 日游）脱敏。
 * </p>
 */
class CollabDtoRoundTripTest {

    @TempDir
    Path tempDir;

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("intake_result.json", IntakeResult.class, intakeResult()),
                Arguments.of("task_backlog.json", TaskBacklog.class, taskBacklog()),
                Arguments.of("poi_shortlist.json", PoiShortlist.class, poiShortlist()),
                Arguments.of("route_plan.json", RoutePlan.class, routePlan()),
                Arguments.of("execution_result.json", ExecutionResult.class, executionResult()),
                Arguments.of("itinerary_draft.json", ItineraryDraft.class, itineraryDraft()),
                Arguments.of("review_report.json", ReviewReport.class, reviewReport()),
                Arguments.of("review_passed.json", ReviewPassed.class, reviewPassed()),
                Arguments.of("session_meta.json", SessionMeta.class, sessionMeta()));
    }

    @ParameterizedTest(name = "{0} 三态往返 + fixture 一致")
    @MethodSource("cases")
    void roundTrip(String fileName, Class<?> type, Object dto) throws Exception {
        // 断言 1：fastjson2 序列化 → 反序列化相等
        String json = JSON.toJSONString(dto);
        assertThat(JSON.parseObject(json, type)).isEqualTo(dto);

        // 断言 2：CollabFileWriter 原子落盘 → CollabFileReader 校验读回相等
        CollabFileWriter writer = new CollabFileWriter(tempDir.toString());
        CollabFileReader reader = new CollabFileReader();
        writer.write(tempDir, fileName, dto);
        var readBack = reader.read(tempDir, fileName, type);
        assertThat(readBack).isPresent();
        assertThat(readBack.get()).isEqualTo(dto);

        // 断言 3：序列化输出过 Schema 校验不抛且相等
        assertThat(CollabSchemaValidator.parse(json, type)).isEqualTo(dto);

        // 断言 4：resources 下的 fixture 文件过校验且与代码构造一致（fixture 即契约样例文档）
        var resource = getClass().getResource("/collab/" + fileName);
        assertThat(resource).as("fixture 缺失: %s", fileName).isNotNull();
        String fixtureText = Files.readString(Path.of(resource.toURI()));
        assertThat(CollabSchemaValidator.parse(fixtureText, type)).isEqualTo(dto);
    }

    // ==================== 9 个 DTO 样例（与 resources/collab/*.json 逐字段一致） ====================

    private static IntakeResult intakeResult() {
        return new IntakeResult("conv-97", IntakeResult.Status.DONE, 1,
                new IntakeResult.RequirementFields(
                        new IntakeResult.FieldValue("杭州", true),
                        new IntakeResult.FieldValue("2", true),
                        new IntakeResult.FieldValue("2026-10-25", true),
                        new IntakeResult.FieldValue("上海", true),
                        new IntakeResult.FieldValue("1500 元（2 人总计）", true),
                        new IntakeResult.FieldValue("自然风景、人文古迹", true),
                        new IntakeResult.FieldValue("2", true),
                        new IntakeResult.FieldValue("无", false, "默认值")),
                false, "2026-10-15 21:30:00");
    }

    private static TaskBacklog taskBacklog() {
        return new TaskBacklog("conv-97", List.of(
                new TaskBacklog.TaskItem("T1", "查询上海到杭州往返火车票（2026-10-25 去程）",
                        "train-ticket-query", "P0", "DONE", "2026-10-15 21:35:00"),
                new TaskBacklog.TaskItem("T2", "查询杭州 10-25/26 天气预报",
                        "weather-query", "P0", "DONE", "2026-10-15 21:35:10"),
                new TaskBacklog.TaskItem("T3", "检索杭州景点候选（自然风景、人文古迹）",
                        "attraction-search", "P0", "DONE", "2026-10-15 21:36:00"),
                new TaskBacklog.TaskItem("T4", "搜索杭州西湖周边酒店（双人大床房）",
                        "hotel-search", "P1", "DONE", "2026-10-15 21:36:30"),
                new TaskBacklog.TaskItem("T5", "分日路线规划（就近串联）",
                        "route-planning", "P1", "DONE", "2026-10-15 21:37:00")));
    }

    private static PoiShortlist poiShortlist() {
        return new PoiShortlist("杭州", "5 变体并行检索，去重后 8 个候选", List.of(
                new PoiShortlist.PoiItem("西湖风景名胜区", 120.1449, 30.2436, 4.8,
                        "全天开放", 240, "RAG+ES", "杭州地标，湖光山色"),
                new PoiShortlist.PoiItem("灵隐寺", 120.0962, 30.2435, 4.7,
                        "07:00-18:00", 150, "RAG(chunk=c-1024)", "千年古刹，人文古迹"),
                new PoiShortlist.PoiItem("雷峰塔", 120.1486, 30.2312, 4.6,
                        "08:00-17:30", 120, "ES(keyword=西湖)", "西湖十景之一")));
    }

    private static RoutePlan routePlan() {
        return new RoutePlan(List.of(
                new RoutePlan.RouteDay(1, "2026-10-25", List.of(
                        new RoutePlan.RoutePoi("西湖风景名胜区", 120.1449, 30.2436,
                                "09:00", "13:00", null, null),
                        new RoutePlan.RoutePoi("雷峰塔", 120.1486, 30.2312,
                                "14:00", "16:00", "公交", 25)), 25, "3f9a1c82d4e5b607"),
                new RoutePlan.RouteDay(2, "2026-10-26", List.of(
                        new RoutePlan.RoutePoi("灵隐寺", 120.0962, 30.2435,
                                "08:30", "11:00", null, null)), 0, "8b2d4f60a1c93e75")),
                new RoutePlan.SolverMeta("NN+2opt", "amap-transit", 12, List.of()));
    }

    private static ExecutionResult executionResult() {
        return new ExecutionResult("conv-97", List.of(
                new ExecutionResult.TaskResultItem("T1", "DONE",
                        "G7331 08:00 上海虹桥→杭州东，二等座 73 元",
                        List.of("mcp__c12306: 余票充足"), "2026-10-15 21:35:00"),
                new ExecutionResult.TaskResultItem("T2", "DONE",
                        "10-25 晴 18-25℃，10-26 多云", null, "2026-10-15 21:35:10")));
    }

    private static ItineraryDraft itineraryDraft() {
        return new ItineraryDraft(List.of(
                new ItineraryDraft.DraftDay(1, "2026-10-25", "西湖环线", List.of(
                        new ItineraryDraft.DraftItem("08:00-09:00", "G7331 上海虹桥→杭州东",
                                "上海虹桥站", "73 元/人", null),
                        new ItineraryDraft.DraftItem("09:30-13:00", "环湖漫步（断桥-白堤-苏堤）",
                                "西湖风景名胜区", "免费", null),
                        new ItineraryDraft.DraftItem("14:00-16:00", "登雷峰塔观湖景",
                                "雷峰塔", "40 元/人", null))),
                new ItineraryDraft.DraftDay(2, "2026-10-26", "灵隐禅意", List.of(
                        new ItineraryDraft.DraftItem("08:30-11:00", "灵隐寺礼佛",
                                "灵隐寺", "飞来峰 45 元+香花券 30 元/人", null),
                        new ItineraryDraft.DraftItem("15:30-16:30", "G7332 杭州东→上海虹桥",
                                "杭州东站", "73 元/人", null)))),
                new ItineraryDraft.Budget("CNY", "约 680 元/人", List.of(
                        new ItineraryDraft.BudgetItem("交通", "146 元/人", "往返高铁二等座"),
                        new ItineraryDraft.BudgetItem("门票", "115 元/人", "雷峰塔+灵隐"),
                        new ItineraryDraft.BudgetItem("住宿", "260 元/人", "西湖周边大床房 1 晚（2 人摊）"))),
                List.of("周末西湖人多，建议早出发", "灵隐寺需先购飞来峰门票"),
                List.of("返程车次待确认"),
                "# 杭州 2 日行程草案\n\n## 分日行程\n（正文略）");
    }

    private static ReviewReport reviewReport() {
        return new ReviewReport(List.of(
                new ReviewReport.ReviewSegment(1, true, List.of()),
                new ReviewReport.ReviewSegment(2, false,
                        List.of("第 2 天酒店→灵隐寺通勤 95 分钟，超单日通勤上限 90 分钟")),
                new ReviewReport.ReviewSegment(3, true, List.of())),
                new ReviewReport.ReviewScores(18, 16, 12, 18, 17), 81,
                Map.of("2", List.of("将灵隐寺调整至第 1 天下午，第 2 天改为市区近郊景点")),
                "2026-10-15 21:40:00");
    }

    private static ReviewPassed reviewPassed() {
        return new ReviewPassed(85, 15,
                List.of("调 getTransitRoute 重算西湖→灵隐寺通勤，实测 38 分钟与草案一致"),
                List.of(new ReviewPassed.ReviewWarning("BUDGET_DEVIATION",
                        "预算超支：估算 1658 vs 预算上限 1500（偏差 10%）",
                        "偏差在 10%~15% 贴线区间，可按当前预算交付或让规划调整住宿/交通档位")),
                "2026-10-15 21:42:00");
    }

    private static SessionMeta sessionMeta() {
        return new SessionMeta("1", "conv-97", "2026-10-15 21:00:00", "已完成",
                List.of("travel-master", "itinerary-agent", "reviewer-agent"),
                "2026-10-15 21:42:30");
    }
}
