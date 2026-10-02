package com.travelscope.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EvalCaseLoader 单元测试（FR-S15 验收：加载 10 条用例、字段校验通过）
 */
class EvalCaseLoaderTest {

    private static final String CATEGORY = "\"FAMILY\"";
    private static final String MESSAGES = "[\"你好，帮我规划一份行程\"]";
    private static final String EXPECTATIONS =
            "{\"rules\": [{\"type\": \"TOOL_ORDER\", \"desc\": \"先 POI 后路线后组装\"}], \"rubric\": {\"minTotal\": 80}}";

    @Test
    @DisplayName("加载全部用例：10 条、编号 EC-001..EC-010 有序且唯一")
    void loadAllReturnsTenCasesInOrder() {
        List<EvalCase> cases = EvalCaseLoader.loadAll();
        assertEquals(10, cases.size(), "用例总数应为 10");
        for (int i = 0; i < cases.size(); i++) {
            String expected = "EC-" + String.format("%03d", i + 1);
            assertEquals(expected, cases.get(i).caseId(), "第 " + (i + 1) + " 条用例编号应为 " + expected);
        }
        assertEquals(10, cases.stream().map(EvalCase::caseId).distinct().count(), "caseId 不得重复");
    }

    @Test
    @DisplayName("逐条字段校验：类别/多轮消息/规则/参数/rubric 全部合法")
    void validatesAllCaseFields() {
        for (EvalCase evalCase : EvalCaseLoader.loadAll()) {
            String id = evalCase.caseId();
            assertNotNull(evalCase.category(), id + ": category 不能为空");
            assertFalse(evalCase.userMessages().isEmpty(), id + ": userMessages 不能为空");
            evalCase.userMessages().forEach(message ->
                    assertFalse(message.isBlank(), id + ": userMessages 含空白消息"));
            assertNotNull(evalCase.expectations(), id + ": expectations 不能为空");

            List<EvalCase.Rule> rules = evalCase.expectations().rules();
            assertFalse(rules.isEmpty(), id + ": rules 不能为空");
            for (EvalCase.Rule rule : rules) {
                assertNotNull(rule.type(), id + ": 规则类型不能为空");
                assertNotNull(rule.params(), id + ": params 应规范化为非 null");
                if (rule.type() == RuleType.CONSTRAINT_COVERED) {
                    String keyword = rule.params().get("keyword");
                    assertFalse(keyword == null || keyword.isBlank(),
                            id + ": CONSTRAINT_COVERED 必须带非空 keyword");
                }
                if (rule.type() == RuleType.NO_HALLUCINATION) {
                    String poi = rule.params().get("poi");
                    assertFalse(poi == null || poi.isBlank(),
                            id + ": NO_HALLUCINATION 必须带非空 poi");
                }
            }
            int minTotal = evalCase.expectations().rubric().minTotal();
            assertTrue(minTotal >= 1 && minTotal <= 100,
                    id + ": minTotal 应在 1~100，实际: " + minTotal);
        }
    }

    @Test
    @DisplayName("5 类场景每类恰好 2 条")
    void coversAllCategoriesWithTwoCasesEach() {
        Map<EvalCategory, Long> counts = EvalCaseLoader.loadAll().stream()
                .collect(Collectors.groupingBy(EvalCase::category, Collectors.counting()));
        for (EvalCategory category : EvalCategory.values()) {
            assertEquals(2L, counts.getOrDefault(category, 0L),
                    category + " 应恰好 2 条，实际: " + counts.get(category));
        }
    }

    @Test
    @DisplayName("规则按类别配置：POI_NOT_EXIST 配 NO_HALLUCINATION、STRICT_BUDGET 配 CONSTRAINT_COVERED、ROUTE_TOO_DENSE 配 TIME_CONFLICT_HANDLED")
    void assignsRulesByCategory() {
        for (EvalCase evalCase : EvalCaseLoader.loadAll()) {
            List<RuleType> types = evalCase.expectations().rules().stream()
                    .map(EvalCase.Rule::type).toList();
            String context = evalCase.caseId() + "（" + evalCase.category() + "）";
            switch (evalCase.category()) {
                case POI_NOT_EXIST -> assertTrue(types.contains(RuleType.NO_HALLUCINATION),
                        context + " 应包含 NO_HALLUCINATION");
                case STRICT_BUDGET -> assertTrue(types.contains(RuleType.CONSTRAINT_COVERED),
                        context + " 应包含 CONSTRAINT_COVERED");
                case ROUTE_TOO_DENSE -> assertTrue(types.contains(RuleType.TIME_CONFLICT_HANDLED),
                        context + " 应包含 TIME_CONFLICT_HANDLED");
                default -> assertTrue(types.contains(RuleType.FIELD_COMPLETE) && types.contains(RuleType.TOOL_ORDER),
                        context + " 应包含 FIELD_COMPLETE 与 TOOL_ORDER");
            }
        }
    }

    @Test
    @DisplayName("非法用例在加载期被拒绝（fail-fast，错误消息可定位）")
    void rejectsInvalidCaseJson() {
        // 非法 JSON 语法
        assertRejected("{ 非法 JSON", "JSON 解析失败");
        // 尾随内容
        assertRejected(caseJson("EC-900", CATEGORY, MESSAGES, EXPECTATIONS, "") + " 垃圾",
                "JSON 结束后存在多余内容");
        // 顶层未知字段
        assertRejected(caseJson("EC-900", CATEGORY, MESSAGES, EXPECTATIONS, ", \"extra\": 1"),
                "未知字段 'extra'");
        // 未知类别
        assertRejected(caseJson("EC-900", "\"NOT_A_CATEGORY\"", MESSAGES, EXPECTATIONS, ""),
                "未知取值");
        // caseId 格式非法
        assertRejected(caseJson("X-1", CATEGORY, MESSAGES, EXPECTATIONS, ""),
                "caseId 非法");
        // userMessages 为空数组
        assertRejected(caseJson("EC-900", CATEGORY, "[]", EXPECTATIONS, ""),
                "userMessages 必须是非空数组");
        // 缺少 rules
        assertRejected(caseJson("EC-900", CATEGORY, MESSAGES, "{\"rubric\": {\"minTotal\": 80}}", ""),
                "缺少必填字段 'rules'");
        // rules 为空数组
        assertRejected(caseJson("EC-900", CATEGORY, MESSAGES, "{\"rules\": [], \"rubric\": {\"minTotal\": 80}}", ""),
                "rules 必须是非空数组");
        // 规则缺少 type
        assertRejected(caseJson("EC-900", CATEGORY, MESSAGES,
                        "{\"rules\": [{\"desc\": \"x\"}], \"rubric\": {\"minTotal\": 80}}", ""),
                "缺少必填字段 'type'");
        // 未知规则类型
        assertRejected(caseJson("EC-900", CATEGORY, MESSAGES,
                        "{\"rules\": [{\"type\": \"NOT_A_RULE\"}], \"rubric\": {\"minTotal\": 80}}", ""),
                "未知取值");
        // CONSTRAINT_COVERED 缺 keyword
        assertRejected(caseJson("EC-900", CATEGORY, MESSAGES,
                        "{\"rules\": [{\"type\": \"CONSTRAINT_COVERED\"}], \"rubric\": {\"minTotal\": 80}}", ""),
                "params.keyword");
        // NO_HALLUCINATION 缺 poi
        assertRejected(caseJson("EC-900", CATEGORY, MESSAGES,
                        "{\"rules\": [{\"type\": \"NO_HALLUCINATION\"}], \"rubric\": {\"minTotal\": 80}}", ""),
                "params.poi");
        // params 值非字符串
        assertRejected(caseJson("EC-900", CATEGORY, MESSAGES,
                        "{\"rules\": [{\"type\": \"CONSTRAINT_COVERED\", \"params\": {\"keyword\": 3000}}], \"rubric\": {\"minTotal\": 80}}", ""),
                "参数值必须是字符串");
        // rubric.minTotal 超范围
        assertRejected(caseJson("EC-900", CATEGORY, MESSAGES,
                        "{\"rules\": [{\"type\": \"TOOL_ORDER\"}], \"rubric\": {\"minTotal\": 120}}", ""),
                "1~100");
        // rubric 缺 minTotal
        assertRejected(caseJson("EC-900", CATEGORY, MESSAGES,
                        "{\"rules\": [{\"type\": \"TOOL_ORDER\"}], \"rubric\": {}}", ""),
                "缺少必填字段 'minTotal'");
    }

    @Test
    @DisplayName("文件名主干与 caseId 不一致时拒绝加载")
    void rejectsFileNameCaseIdMismatch(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("EC-999.json");
        Files.writeString(file, caseJson("EC-001", CATEGORY, MESSAGES, EXPECTATIONS, ""),
                StandardCharsets.UTF_8);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EvalCaseLoader.load(file));
        assertTrue(e.getMessage().contains("不一致"),
                "错误消息应指出文件名与 caseId 不一致，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("MiniJson 基础能力：转义/Unicode/嵌套/数字类型/布尔与 null")
    void parsesJsonBasics() {
        Map<?, ?> root = (Map<?, ?>) MiniJson.parse("""
                {"s":"a\\"b\\\\c\\n\\u4e2d","i":42,"d":4.5,"neg":-7,"t":true,"f":false,"n":null,"list":[{"k":"v"}]}
                """);
        assertEquals("a\"b\\c\n中", root.get("s"), "转义与 Unicode 应正确还原");
        assertEquals(42L, root.get("i"), "整数应解析为 Long");
        assertEquals(4.5, root.get("d"), "小数应解析为 Double");
        assertEquals(-7L, root.get("neg"), "负整数应正确解析");
        assertEquals(Boolean.TRUE, root.get("t"));
        assertEquals(Boolean.FALSE, root.get("f"));
        assertNull(root.get("n"));
        List<?> list = (List<?>) root.get("list");
        assertEquals(1, list.size(), "数组长度应为 1");
        assertEquals(Map.of("k", "v"), list.get(0), "嵌套对象应正确解析");
    }

    /** 断言给定 JSON 被 fromJson 拒绝，且错误消息包含关键片段 */
    private static void assertRejected(String json, String messagePart) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EvalCaseLoader.fromJson(json, "inline"));
        assertTrue(e.getMessage().contains(messagePart),
                "错误消息应包含「" + messagePart + "」，实际: " + e.getMessage());
    }

    /** 构造一份用例 JSON，category/messages/expectations/extra 均为 JSON 片段文本，便于按需破坏某一处 */
    private static String caseJson(String caseId, String category, String messages,
                                   String expectations, String extra) {
        return """
                {
                  "caseId": "%s",
                  "category": %s,
                  "userMessages": %s,
                  "expectations": %s%s
                }
                """.formatted(caseId, category, messages, expectations, extra);
    }
}
