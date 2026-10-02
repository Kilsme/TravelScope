package com.travelscope.eval;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rubric 评分结果（qwen-max 三维 10 分制，FR-S15 双轨评估的 Rubric 轨）。
 * <p>
 * 结构化输出用 POJO（IntentResult 同款：public String 字段接住模型输出再安全归一化，
 * 避免模型输出 "8分"/小数导致反序列化失败）。三维之和满分 30，按百分制折算
 * （{@link #normalizedTotal()}）与用例 rubric.minTotal（1~100，当前 80）同口径比较。
 * </p>
 */
public class RubricScores {

    /** 路线连贯性：景点就近聚类、动线合理、多城市衔接顺畅 */
    public String routeCoherence;
    /** 时间密度：张弛有度、通勤可控（单日 ≤90 分钟）、无不可能时刻表 */
    public String timeDensity;
    /** 偏好匹配：天数/城市/预算/同行人/特殊偏好被满足 */
    public String preferenceMatch;

    public RubricScores() {
    }

    public int routeCoherenceScore() {
        return clampScore(routeCoherence);
    }

    public int timeDensityScore() {
        return clampScore(timeDensity);
    }

    public int preferenceMatchScore() {
        return clampScore(preferenceMatch);
    }

    public int sum() {
        return routeCoherenceScore() + timeDensityScore() + preferenceMatchScore();
    }

    /** 百分制折算（满分 30 → 100），与用例 rubric.minTotal 同口径 */
    public int normalizedTotal() {
        return Math.round(sum() * 100f / 30);
    }

    /** 三维是否全部有值（结构化路径的有效性判断） */
    public boolean complete() {
        return routeCoherence != null && timeDensity != null && preferenceMatch != null;
    }

    /**
     * 宽容解析模型原始输出（结构化路径失败时的纯文本兜底，A5 翻车应急预案）：
     * 先截取首尾大括号间的 JSON 对象解析（MiniJson，模型可能带 ```json 围栏或前后缀说明），
     * 失败退正则按英文/中文键名逐维取数。
     *
     * @return 三维齐全的评分；无法解析返回 null（调用方负责重试/判 FAIL）
     */
    public static RubricScores parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        RubricScores fromJson = parseJsonObject(extractJsonBody(text));
        if (fromJson != null) {
            return fromJson;
        }
        RubricScores byRegex = new RubricScores();
        byRegex.routeCoherence = findScore(text, "routeCoherence", "路线连贯");
        byRegex.timeDensity = findScore(text, "timeDensity", "时间密度");
        byRegex.preferenceMatch = findScore(text, "preferenceMatch", "偏好匹配");
        return byRegex.complete() ? byRegex : null;
    }

    private static RubricScores parseJsonObject(String json) {
        if (json == null) {
            return null;
        }
        try {
            if (MiniJson.parse(json) instanceof Map<?, ?> map) {
                RubricScores scores = new RubricScores();
                scores.routeCoherence = scoreValue(map.get("routeCoherence"));
                scores.timeDensity = scoreValue(map.get("timeDensity"));
                scores.preferenceMatch = scoreValue(map.get("preferenceMatch"));
                return scores.complete() ? scores : null;
            }
        } catch (Exception ignored) {
            // 非法 JSON 落正则路径
        }
        return null;
    }

    /** 截取首个 '{' 到最后一个 '}' 之间的内容；无完整对象返回 null */
    private static String extractJsonBody(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    /** JSON 值（Long/String）转可打分字符串；无数字的值视为缺失返回 null */
    private static String scoreValue(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.chars().anyMatch(Character::isDigit) ? s : null;
    }

    /** 正则逐维取数：键名后 8 个非数字字符内的 1~2 位数字（如 "时间密度：8分"） */
    private static String findScore(String text, String enKey, String zhKey) {
        for (String key : List.of(enKey, zhKey)) {
            Matcher matcher = Pattern.compile(key + "[^0-9]{0,8}([0-9]{1,2})").matcher(text);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /** 安全归一化：解析失败提取数字（"8分"）；越界钳制到 0~10 */
    private static int clampScore(String raw) {
        if (raw == null) {
            return 0;
        }
        String trimmed = raw.trim();
        try {
            return clamp(Integer.parseInt(trimmed));
        } catch (NumberFormatException e) {
            Matcher matcher = Pattern.compile("[0-9]+").matcher(trimmed);
            return matcher.find() ? clamp(Integer.parseInt(matcher.group())) : 0;
        }
    }

    private static int clamp(int score) {
        return Math.max(0, Math.min(10, score));
    }
}
