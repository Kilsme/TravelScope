package com.travelscope.service;

import com.travelscope.collab.CollabFileReader;
import com.travelscope.collab.CollabFileWriter;
import com.travelscope.collab.IntakeResult;
import com.travelscope.collab.ItineraryDraft;
import com.travelscope.collab.ItineraryDraft.Budget;
import com.travelscope.collab.ItineraryDraft.DraftDay;
import com.travelscope.collab.ItineraryDraft.DraftItem;
import com.travelscope.collab.PoiShortlist;
import com.travelscope.collab.RoutePlan;
import com.travelscope.collab.TaskWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RuleReviewService（质检降级为规则校验）单测。
 * TaskWorkspace 真实（@TempDir 落盘契约产物），纯 Java 规则无 LLM 依赖。
 */
class RuleReviewServiceTest {

    @TempDir
    Path tempDir;

    private TaskWorkspace workspace;
    private RuleReviewService ruleReviewService;

    @BeforeEach
    void setUp() {
        workspace = new TaskWorkspace(new CollabFileWriter(tempDir.toString()), new CollabFileReader());
        ruleReviewService = new RuleReviewService(workspace);
    }

    // ---------- 测试数据 ----------

    private static IntakeResult intake(String days, String destination, String fromCity,
                                       String budget, String preference) {
        return new IntakeResult("conv-r", IntakeResult.Status.DONE, 0,
                new IntakeResult.RequirementFields(
                        fv(destination), fv(days), fv("2026-10-25"), fv(fromCity),
                        fv(budget), fv(preference), fv(null), fv(null)),
                false, "2026-10-20 10:00:00");
    }

    private static IntakeResult.FieldValue fv(String value) {
        return value == null ? null : new IntakeResult.FieldValue(value, true, null);
    }

    private static ItineraryDraft draft(List<DraftItem> day1Items, String totalEstimate,
                                        List<String> notes, List<String> pending) {
        DraftDay day1 = new DraftDay(1, "2026-10-25", "苏州园林", day1Items);
        return new ItineraryDraft(List.of(day1),
                totalEstimate == null ? null : new Budget("CNY", totalEstimate, List.of()),
                notes, pending, "# 苏州行程\n拙政园、平江路");
    }

    private static PoiShortlist shortlist() {
        return new PoiShortlist("苏州", "候选池", List.of(
                new PoiShortlist.PoiItem("拙政园", 120.6, 31.3, 4.6, "07:30-17:30", 120, "飞猪", "园林首选"),
                new PoiShortlist.PoiItem("平江路历史街区", 120.6, 31.3, 4.5, null, 90, "高德", "古城水乡")));
    }

    private static RoutePlan cleanRoutePlan() {
        return new RoutePlan(List.of(), new RoutePlan.SolverMeta("NN+2opt", "amap", 27, List.of()));
    }

    private void seedAll(ItineraryDraft d) {
        workspace.writeIntakeResult("u1", "conv-r", intake("1", "苏州", "上海", "1200", "园林"));
        workspace.writeItineraryDraft("u1", "conv-r", d);
        workspace.writePoiShortlist("u1", "conv-r", shortlist());
        workspace.writeRoutePlan("u1", "conv-r", cleanRoutePlan());
    }

    // ---------- 用例 ----------

    @Test
    @DisplayName("全过场景：写 review_passed.json，total≥80 且三段全 pass")
    void allPassWritesReviewPassed() {
        // 出发交通项对齐真实组装产物（大交通段含出发地「上海」；含 站/出发 关键词豁免 POI 校验）
        seedAll(draft(List.of(
                new DraftItem("08:00-08:40", "上海出发 乘高铁至苏州", "上海虹桥站", "¥39", null),
                new DraftItem("09:00-11:30", "游览拙政园", "拙政园", "¥80", null),
                new DraftItem("13:00-15:00", "平江路漫步", "平江路历史街区", "免费", null)),
                "约 1100 元", List.of(), List.of()));

        RuleReviewService.Outcome outcome = ruleReviewService.check("u1", "conv-r");

        assertThat(outcome.passed()).isTrue();
        var passed = workspace.readReviewPassed("u1", "conv-r");
        assertThat(passed).isPresent();
        assertThat(passed.get().total()).isGreaterThanOrEqualTo(80);
        assertThat(passed.get().minDimension()).isGreaterThanOrEqualTo(12);
        assertThat(passed.get().verified()).isNotEmpty();
    }

    @Test
    @DisplayName("天数不符 → 段1 fail，写 review_report.json")
    void daysMismatchFailsSegment1() {
        workspace.writeIntakeResult("u1", "conv-r", intake("3", "苏州", "上海", "1200", "园林"));
        workspace.writeItineraryDraft("u1", "conv-r", draft(List.of(
                new DraftItem("09:00-11:30", "游览拙政园", "拙政园", "¥80", null)),
                "约 1100 元", List.of(), List.of())); // 只有 1 天，intake 要 3 天
        workspace.writePoiShortlist("u1", "conv-r", shortlist());
        workspace.writeRoutePlan("u1", "conv-r", cleanRoutePlan());

        RuleReviewService.Outcome outcome = ruleReviewService.check("u1", "conv-r");

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.issues()).anyMatch(i -> i.contains("天数不符"));
        var report = workspace.readReviewReport("u1", "conv-r");
        assertThat(report).isPresent();
        assertThat(report.get().segments().get(0).pass()).isFalse();
    }

    @Test
    @DisplayName("时间线重叠 → 段2 fail")
    void timelineOverlapFailsSegment2() {
        seedAll(draft(List.of(
                new DraftItem("09:00-11:30", "游览拙政园", "拙政园", "¥80", null),
                new DraftItem("11:00-12:00", "平江路漫步", "平江路历史街区", "免费", null)), // 11:00 < 11:30 重叠
                "约 1100 元", List.of(), List.of()));

        RuleReviewService.Outcome outcome = ruleReviewService.check("u1", "conv-r");

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.issues()).anyMatch(i -> i.contains("早于上一项"));
    }

    @Test
    @DisplayName("预算超支 >10% → 段3 fail")
    void budgetOverrunFailsSegment3() {
        seedAll(draft(List.of(
                new DraftItem("09:00-11:30", "游览拙政园", "拙政园", "¥80", null)),
                "约 2000 元", List.of(), List.of())); // 预算 1200，超 67%

        RuleReviewService.Outcome outcome = ruleReviewService.check("u1", "conv-r");

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.issues()).anyMatch(i -> i.contains("预算超支"));
    }

    @Test
    @DisplayName("偏好未覆盖（要古镇但行程无）→ 段3 fail")
    void uncoveredPreferenceFailsSegment3() {
        workspace.writeIntakeResult("u1", "conv-r", intake("1", "苏州", "上海", "1200", "古镇"));
        workspace.writeItineraryDraft("u1", "conv-r", draft(List.of(
                new DraftItem("09:00-11:30", "游览拙政园", "拙政园", "¥80", null)),
                "约 1100 元", List.of(), List.of())); // 只排了园林，古镇无覆盖
        workspace.writePoiShortlist("u1", "conv-r", shortlist());
        workspace.writeRoutePlan("u1", "conv-r", cleanRoutePlan());

        RuleReviewService.Outcome outcome = ruleReviewService.check("u1", "conv-r");

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.issues()).anyMatch(i -> i.contains("古镇") && i.contains("未在行程中体现"));
    }

    @Test
    @DisplayName("偏好经 notes 显式披露取舍 → 段3 pass（含关键词即算覆盖）")
    void disclosedPreferencePasses() {
        workspace.writeIntakeResult("u1", "conv-r", intake("1", "苏州", "上海", "1200", "古镇"));
        workspace.writeItineraryDraft("u1", "conv-r", draft(List.of(
                new DraftItem("08:00-08:40", "上海出发 乘高铁至苏州", "上海虹桥站", "¥39", null),
                new DraftItem("09:00-11:30", "游览拙政园", "拙政园", "¥80", null)),
                "约 1100 元",
                List.of("同里古镇因距离未纳入，偏好「古镇」以平江路古城水乡街区替代呈现"), List.of()));
        workspace.writePoiShortlist("u1", "conv-r", shortlist());
        workspace.writeRoutePlan("u1", "conv-r", cleanRoutePlan());

        RuleReviewService.Outcome outcome = ruleReviewService.check("u1", "conv-r");

        assertThat(outcome.passed()).isTrue();
    }

    @Test
    @DisplayName("防幻觉：候选池外 POI → 检出并记段1 问题")
    void hallucinatedPoiDetected() {
        seedAll(draft(List.of(
                new DraftItem("09:00-11:30", "游览幽灵古镇", "幽灵古镇", "¥80", null)), // 候选池没有
                "约 1100 元", List.of(), List.of()));

        RuleReviewService.Outcome outcome = ruleReviewService.check("u1", "conv-r");

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.issues()).anyMatch(i -> i.contains("疑似幻觉"));
    }

    @Test
    @DisplayName("评分满足 Schema 校验：total 等于五维之和（±1 内）")
    void scoresSumToTotal() {
        seedAll(draft(List.of(
                new DraftItem("09:00-11:30", "游览幽灵古镇", "幽灵古镇", null, null)),
                "约 2000 元", List.of(), List.of())); // 幻觉 + 超预算

        ruleReviewService.check("u1", "conv-r");

        var report = workspace.readReviewReport("u1", "conv-r");
        assertThat(report).isPresent();
        var s = report.get().scores();
        int sum = s.completeness() + s.feasibility() + s.timeConflict() + s.costBudget() + s.poiReasonableness();
        assertThat(Math.abs(report.get().total() - sum)).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("review_passed.json 粘滞：已通过的产物不被后续规则失败覆盖")
    void passedArtifactIsSticky() {
        workspace.writeIntakeResult("u1", "conv-r", intake("1", "苏州", "上海", "1200", "园林"));
        workspace.writeReviewPassed("u1", "conv-r",
                new com.travelscope.collab.ReviewPassed(91, 15, List.of("已通过"), "2026-10-20 10:00:00"));
        workspace.writeItineraryDraft("u1", "conv-r", draft(List.of(
                new DraftItem("09:00-11:30", "游览幽灵古镇", "幽灵古镇", null, null)),
                "约 2000 元", List.of(), List.of())); // 一个本应失败的草案

        RuleReviewService.Outcome outcome = ruleReviewService.check("u1", "conv-r");

        assertThat(outcome.passed()).isTrue();
        assertThat(workspace.readReviewReport("u1", "conv-r")).isEmpty();
        assertThat(workspace.readReviewPassed("u1", "conv-r")).isPresent();
    }

    @Test
    @DisplayName("无草案产物 → 跳过质检（passed=true，不写任何产物）")
    void noDraftSkipsReview() {
        RuleReviewService.Outcome outcome = ruleReviewService.check("u1", "conv-r");

        assertThat(outcome.passed()).isTrue();
        assertThat(workspace.readReviewPassed("u1", "conv-r")).isEmpty();
        assertThat(workspace.readReviewReport("u1", "conv-r")).isEmpty();
    }
}
