package com.travelscope.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RubricScores 宽容解析与分数口径单元测试（FR-S15/A5：纯函数、无门控常跑）
 */
class RubricScoresTest {

    @Test
    @DisplayName("parse：干净 JSON 对象 → 三维齐全")
    void parsesCleanJson() {
        RubricScores scores = RubricScores.parse("{\"routeCoherence\": 8, \"timeDensity\": 7, \"preferenceMatch\": 9}");
        assertNotNull(scores);
        assertEquals(8, scores.routeCoherenceScore());
        assertEquals(7, scores.timeDensityScore());
        assertEquals(9, scores.preferenceMatchScore());
        assertEquals(24, scores.sum());
        assertEquals(80, scores.normalizedTotal(), "24/30 应折算为 80/100（minTotal=80 口径）");
    }

    @Test
    @DisplayName("parse：```json 围栏与前后缀说明 → 截取 JSON 体解析")
    void parsesFencedJson() {
        RubricScores scores = RubricScores.parse("""
                评审结果如下：
                ```json
                {"routeCoherence": 10, "timeDensity": 9, "preferenceMatch": 10}
                ```
                以上为最终评分。""");
        assertNotNull(scores);
        assertEquals(29, scores.sum());
        assertEquals(97, scores.normalizedTotal());
    }

    @Test
    @DisplayName("parse：非 JSON 中文文本 → 正则逐维取数兜底")
    void parsesPlainTextByRegex() {
        RubricScores scores = RubricScores.parse("路线连贯性：8分\n时间密度：7分\n偏好匹配：9分");
        assertNotNull(scores);
        assertEquals(8, scores.routeCoherenceScore());
        assertEquals(7, scores.timeDensityScore());
        assertEquals(9, scores.preferenceMatchScore());
    }

    @Test
    @DisplayName("parse：无法解析的输出返回 null（调用方重试/判 FAIL）")
    void returnsNullOnGarbage() {
        assertNull(RubricScores.parse("抱歉我无法评分"));
        assertNull(RubricScores.parse(""));
        assertNull(RubricScores.parse(null));
        assertNull(RubricScores.parse("{\"foo\": 1}"), "缺三维键应视为无效");
    }

    @Test
    @DisplayName("分数归一化：越界钳制 0~10、带单位字符串提取数字")
    void clampsAndExtractsScores() {
        RubricScores scores = new RubricScores();
        scores.routeCoherence = "15";
        scores.timeDensity = "-2";
        scores.preferenceMatch = "8分";
        assertEquals(10, scores.routeCoherenceScore(), "15 应钳制为 10");
        assertEquals(0, scores.timeDensityScore(), "-2 应钳制为 0");
        assertEquals(8, scores.preferenceMatchScore(), "「8分」应提取 8");
        assertEquals(18, scores.sum());
        assertEquals(60, scores.normalizedTotal());
        assertTrue(scores.complete());
    }

    @Test
    @DisplayName("partial 结构化结果视为不完整")
    void detectsIncompleteScores() {
        RubricScores scores = new RubricScores();
        scores.routeCoherence = "8";
        assertFalse(scores.complete(), "缺两维应不完整");
        assertEquals(0, scores.timeDensityScore(), "缺失维度按 0 计");
    }
}
