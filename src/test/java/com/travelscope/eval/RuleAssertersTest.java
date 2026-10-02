package com.travelscope.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确定性规则断言器单元测试（FR-S15/A5：纯函数、无门控常跑——
 * EVAL_REAL_API 关闭时 mvn test 里规则轨的质量保障）
 */
class RuleAssertersTest {

    private static final String COMPLETE_ITINERARY = """
            # 北京 3 日亲子游方案

            ## 第1天
            - 09:00 天安门广场（交通：地铁1号线天安门东站）
            - 13:30 故宫博物院
            - 17:00 景山公园
            - 天气提示：晴，12~22℃，适合户外

            ## 第2天
            - 08:30 北京动物园（看大熊猫）
            - 14:00 国家博物馆（午休后出发）

            ## 第3天
            - 09:00 颐和园 → 圆明园

            ## 住宿
            王府井附近亲子酒店，三晚不变

            ## 预算汇总（一家三口）
            | 项目 | 金额 |
            | 交通 | 600元 |
            | 住宿 | 2400元 |
            | 总计 | 8000元 |
            """;

    @Test
    @DisplayName("FIELD_COMPLETE：四维齐备的行程草案 PASS（天数/排期/预算/交通住宿天气）")
    void fieldCompletePassesOnCompleteItinerary() {
        EvalRunArtifact artifact = new EvalRunArtifact(null, COMPLETE_ITINERARY, "poi", "route", null);
        RuleAssertion assertion = RuleAsserters.fieldComplete(artifact);
        assertTrue(assertion.passed(), "应通过，明细: " + assertion.details());
    }

    @Test
    @DisplayName("FIELD_COMPLETE：缺住宿/天气维度 FAIL，且行程缺失时用最终回复兜底")
    void fieldCompleteFailsOnMissingDimensions() {
        String replyOnly = """
                ## 第1天
                - 09:00 天安门广场
                - 14:00 故宫（交通：地铁1号线）
                预算总计 3000元
                """;
        EvalRunArtifact artifact = new EvalRunArtifact(replyOnly, null, null, null, null);
        RuleAssertion assertion = RuleAsserters.fieldComplete(artifact);
        assertFalse(assertion.passed(), "缺住宿/天气应 FAIL");
        assertTrue(assertion.details().contains("住宿"), "明细应指出缺住宿，实际: " + assertion.details());
        assertTrue(assertion.details().contains("天气"), "明细应指出缺天气，实际: " + assertion.details());
    }

    @Test
    @DisplayName("FIELD_COMPLETE：完全无产物 FAIL")
    void fieldCompleteFailsOnBlankArtifact() {
        RuleAssertion assertion = RuleAsserters.fieldComplete(new EvalRunArtifact(null, null, null, null, null));
        assertFalse(assertion.passed());
        assertTrue(assertion.details().contains("无行程产物"));
    }

    @Test
    @DisplayName("CONSTRAINT_COVERED：关键词出现于行程/回复 PASS，缺失 FAIL")
    void constraintCoveredByKeyword() {
        EvalCase.Rule rule = new EvalCase.Rule(RuleType.CONSTRAINT_COVERED, null, Map.of("keyword", "天津"));
        EvalRunArtifact hit = new EvalRunArtifact(null, COMPLETE_ITINERARY + "\n第4天 天津之眼", null, null, null);
        assertTrue(RuleAsserters.constraintCovered(rule, hit).passed(), "行程含「天津」应 PASS");

        EvalRunArtifact miss = new EvalRunArtifact("北京三日游方案", null, null, null, null);
        RuleAssertion assertion = RuleAsserters.constraintCovered(rule, miss);
        assertFalse(assertion.passed(), "「天津」未出现应 FAIL");
        assertTrue(assertion.details().contains("天津"));
    }

    @Test
    @DisplayName("NO_HALLUCINATION：回复指出不存在+替代且未编入行程 PASS；编入行程/无否定标记 FAIL")
    void noHallucinationChecks() {
        EvalCase.Rule rule = new EvalCase.Rule(RuleType.NO_HALLUCINATION, null,
                Map.of("poi", "南京路空中花园"));
        String honestReply = "经核实「南京路空中花园」并不存在，为您推荐替代：南京路步行街沿线与外滩观景平台";
        String realItinerary = """
                # 上海 2 日游
                ## 第1天
                - 10:00 南京路步行街
                - 14:00 外滩
                """;
        assertTrue(RuleAsserters.noHallucination(rule,
                        new EvalRunArtifact(honestReply, realItinerary, null, null, null)).passed(),
                "指出不存在+给替代+未编入行程应 PASS");

        String fabricatedItinerary = """
                # 上海 2 日游
                ## 第1天
                - 14:00 南京路空中花园（空中观景）
                """;
        RuleAssertion fabricated = RuleAsserters.noHallucination(rule,
                new EvalRunArtifact(honestReply, fabricatedItinerary, null, null, null));
        assertFalse(fabricated.passed(), "虚构地点被编入行程草案应 FAIL");
        assertTrue(fabricated.details().contains("编入"));

        RuleAssertion noMarker = RuleAsserters.noHallucination(rule,
                new EvalRunArtifact("好的，已为您安排南京路空中花园半日游", null, null, null, null));
        assertFalse(noMarker.passed(), "无任何否定标记应 FAIL");
    }

    @Test
    @DisplayName("TOOL_ORDER：产物时间正序 PASS、倒序 FAIL、缺失 SKIP（@TempDir 造时间戳）")
    void toolOrderChecksTimes(@TempDir Path tempDir) throws IOException {
        writeWithMtime(tempDir.resolve("poi_shortlist.md"), "2026-10-02T10:00:00Z");
        writeWithMtime(tempDir.resolve("route_plan.md"), "2026-10-02T10:05:00Z");
        writeWithMtime(tempDir.resolve("itinerary_draft.md"), "2026-10-02T10:10:00Z");
        EvalRunArtifact ordered = new EvalRunArtifact(null, "方案", "poi", "route", tempDir);
        assertTrue(RuleAsserters.toolOrder(ordered).passed(), "poi→route→itinerary 正序应 PASS");

        writeWithMtime(tempDir.resolve("poi_shortlist.md"), "2026-10-02T10:10:00Z");
        writeWithMtime(tempDir.resolve("route_plan.md"), "2026-10-02T10:00:00Z");
        writeWithMtime(tempDir.resolve("itinerary_draft.md"), "2026-10-02T10:05:00Z");
        RuleAssertion inverted = RuleAsserters.toolOrder(ordered);
        assertFalse(inverted.passed(), "route 早于 poi 应 FAIL");
        assertFalse(inverted.skipped());

        Path emptyDir = tempDir.resolve("empty");
        Files.createDirectories(emptyDir);
        RuleAssertion missing = RuleAsserters.toolOrder(
                new EvalRunArtifact(null, null, null, null, emptyDir));
        assertFalse(missing.passed());
        assertTrue(missing.skipped(), "产物缺失应降级为 SKIP（不算 FAIL），明细: " + missing.details());
        assertTrue(missing.details().contains("poi_shortlist"));
    }

    @Test
    @DisplayName("TIME_CONFLICT_HANDLED：命中拆分/冲突标记 PASS，行程拆多天 PASS，无处理 FAIL")
    void timeConflictHandledChecks() {
        EvalRunArtifact acknowledged = new EvalRunArtifact(
                "一天内逛完这 8 个景点通勤远超 90 分钟，时间上不可行，建议拆分为两天并舍弃八达岭长城", null, null, null, null);
        assertTrue(RuleAsserters.timeConflictHandled(acknowledged).passed(), "明确指出冲突+拆分建议应 PASS");

        String splitItinerary = """
                # 北京 2 日方案（原 1 天 8 景点拆分）
                ## 第1天
                - 09:00 故宫
                ## 第2天
                - 08:30 八达岭长城
                """;
        assertTrue(RuleAsserters.timeConflictHandled(
                        new EvalRunArtifact("已为您调整行程", splitItinerary, null, null, null)).passed(),
                "1 天需求被拆为 2 天方案应 PASS");

        RuleAssertion ignored = RuleAsserters.timeConflictHandled(new EvalRunArtifact(
                "好的，已安排：09:00 故宫，10:00 天坛，11:00 颐和园", null, null, null, null));
        assertFalse(ignored.passed(), "直接输出不可行时刻表且无冲突提示应 FAIL");
    }

    @Test
    @DisplayName("assertRule 分发：五类规则按 type 路由到对应断言器")
    void dispatchesByRuleType() {
        EvalRunArtifact artifact = new EvalRunArtifact(
                "查无此处，推荐替代景点", COMPLETE_ITINERARY, "poi", "route", null);
        assertTrue(RuleAsserters.assertRule(
                new EvalCase.Rule(RuleType.FIELD_COMPLETE, null, Map.of()), artifact).passed());
        assertTrue(RuleAsserters.assertRule(
                new EvalCase.Rule(RuleType.CONSTRAINT_COVERED, null, Map.of("keyword", "北京")), artifact).passed());
        assertTrue(RuleAsserters.assertRule(
                new EvalCase.Rule(RuleType.NO_HALLUCINATION, null, Map.of("poi", "南京路空中花园")), artifact).passed());
        assertTrue(RuleAsserters.assertRule(
                new EvalCase.Rule(RuleType.TIME_CONFLICT_HANDLED, null, Map.of()), artifact).passed());
        assertTrue(RuleAsserters.assertRule(
                new EvalCase.Rule(RuleType.TOOL_ORDER, null, Map.of()), artifact).skipped(),
                "无 taskDir 时 TOOL_ORDER 应 SKIP");
    }

    private static void writeWithMtime(Path file, String isoInstant) throws IOException {
        Files.writeString(file, "content");
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse(isoInstant)));
    }
}
