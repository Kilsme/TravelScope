package com.travelscope.collab;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 工作区契约：review_report.json（schemaVersion=4）
 * <p>
 * 生产者：reviewer-agent（质检不通过时）与 RuleReviewService（规则质检 FAIL 时）；
 * 消费者：itinerary-agent（按失败段回炉）、ChatService（⚠️ 标注 + review_report 事件载荷）。
 * 对应 v3 的 review_report.md；通过标准（总分≥80 + 无单维&lt;12 + 三段全 pass）不变。
 * </p>
 * <p>
 * R4 回炉上限 1（v6）：最终带问题交付时由编排层（ChatService step5）标记
 * {@link #deliveryMode}={@link #DELIVERY_DEGRADED} + {@link #unresolvedIssues} 未解决问题清单
 * （对齐官方「失败时向用户交回控制权」）。两字段可空，旧产物缺键反序列化为 NORMAL/空表，向后兼容。
 * </p>
 * <p>
 * schemaVersion 恒为 {@link #SCHEMA_VERSION}（紧凑构造器强校验）；JSON 键与组件同名。
 * </p>
 */
public record ReviewReport(
        int schemaVersion,
        List<ReviewSegment> segments,
        ReviewScores scores,
        int total,
        Map<String, List<String>> suggestionsBySegment,
        String reviewedAt,
        String deliveryMode,
        List<String> unresolvedIssues) {

    /** 契约版本 */
    public static final int SCHEMA_VERSION = 4;

    /** 交付模式：正常（质检通过或评审中产物，默认） */
    public static final String DELIVERY_NORMAL = "NORMAL";
    /** 交付模式：降级（R4 回炉上限 1 超限/终检仍 FAIL，带未解决问题交付） */
    public static final String DELIVERY_DEGRADED = "DEGRADED";

    public ReviewReport {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "schemaVersion 必须为 " + SCHEMA_VERSION + "，实际: " + schemaVersion);
        }
        segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
        Objects.requireNonNull(scores, "scores");
        if (total < 0 || total > 100) {
            throw new IllegalArgumentException("total 须在 0~100: " + total);
        }
        suggestionsBySegment = deepUnmodifiable(
                Objects.requireNonNull(suggestionsBySegment, "suggestionsBySegment"));
        // reviewedAt 可空
        // R4 两字段可空（旧产物缺键）→ 归一化 NORMAL/空表；非法值快速失败
        if (deliveryMode == null) {
            deliveryMode = DELIVERY_NORMAL;
        } else if (!DELIVERY_NORMAL.equals(deliveryMode) && !DELIVERY_DEGRADED.equals(deliveryMode)) {
            throw new IllegalArgumentException("deliveryMode 取值 NORMAL|DEGRADED，实际: " + deliveryMode);
        }
        unresolvedIssues = unresolvedIssues == null ? List.of()
                : unresolvedIssues.stream().filter(Objects::nonNull).toList();
    }

    /** 便捷构造（v4 原签名）：正常交付模式、无未解决问题——既有调用方与测试零改动 */
    public ReviewReport(List<ReviewSegment> segments, ReviewScores scores, int total,
                        Map<String, List<String>> suggestionsBySegment, String reviewedAt) {
        this(SCHEMA_VERSION, segments, scores, total, suggestionsBySegment, reviewedAt,
                DELIVERY_NORMAL, List.of());
    }

    /** 便捷构造：携带交付模式与未解决问题清单（R4 DEGRADED 交付场景） */
    public ReviewReport(List<ReviewSegment> segments, ReviewScores scores, int total,
                        Map<String, List<String>> suggestionsBySegment, String reviewedAt,
                        String deliveryMode, List<String> unresolvedIssues) {
        this(SCHEMA_VERSION, segments, scores, total, suggestionsBySegment, reviewedAt,
                deliveryMode, unresolvedIssues);
    }

    private static Map<String, List<String>> deepUnmodifiable(Map<String, List<String>> src) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        src.forEach((k, v) -> copy.put(Objects.requireNonNull(k, "suggestionsBySegment 键"),
                v == null ? List.of() : List.copyOf(v)));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * 分段结论。segment 取值：1=POI 有效性，2=路线连贯性+时间密度，3=预算+偏好匹配；
     * issues 为该段问题清单（pass 时为空）。
     */
    public record ReviewSegment(
            int segment,
            boolean pass,
            List<String> issues) {

        public ReviewSegment {
            if (segment < 1 || segment > 3) {
                throw new IllegalArgumentException("segment 取值 1~3，实际: " + segment);
            }
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    /**
     * 5 维评分（各 0~20，满分 100）：完备性 / 可行性 / 时间冲突 / 费用预算 / POI 合理性。
     * 与 v3 reviewer 提示词的评分细则一一对应。
     */
    public record ReviewScores(
            int completeness,
            int feasibility,
            int timeConflict,
            int costBudget,
            int poiReasonableness) {

        public ReviewScores {
            checkDimension("completeness", completeness);
            checkDimension("feasibility", feasibility);
            checkDimension("timeConflict", timeConflict);
            checkDimension("costBudget", costBudget);
            checkDimension("poiReasonableness", poiReasonableness);
        }

        private static void checkDimension(String name, int value) {
            if (value < 0 || value > 20) {
                throw new IllegalArgumentException(name + " 须在 0~20: " + value);
            }
        }
    }
}
