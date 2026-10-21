package com.travelscope.collab;

import java.util.List;
import java.util.Objects;

/**
 * 工作区契约：review_passed.json（schemaVersion=4）
 * <p>
 * 生产者：reviewer-agent（质检通过时）与 RuleReviewService（规则质检，R1 起为常规生产者）；
 * 消费者：ChatService（渲染「已通过质量审阅」标注 + review_report 事件载荷）。
 * 对应 v3 的 review_passed.md；本文件存在即视为通过（ReviewerRetryMiddleware 据此重置回炉计数）。
 * </p>
 * <p>
 * R1 质检分级（v6）：SOFT 违例不回炉、带 warning 交付——软提示随本契约落盘
 * （{@link #warnings}，可空，旧产物缺键反序列化为空表，向后兼容）。评分维度不变。
 * </p>
 * <p>
 * schemaVersion 恒为 {@link #SCHEMA_VERSION}（紧凑构造器强校验）；JSON 键与组件同名。
 * </p>
 */
public record ReviewPassed(
        int schemaVersion,
        int total,
        int minDimension,
        List<String> verified,
        List<ReviewWarning> warnings,
        String passedAt) {

    /** 契约版本 */
    public static final int SCHEMA_VERSION = 4;

    public ReviewPassed {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "schemaVersion 必须为 " + SCHEMA_VERSION + "，实际: " + schemaVersion);
        }
        if (total < 0 || total > 100) {
            throw new IllegalArgumentException("total 须在 0~100: " + total);
        }
        if (minDimension < 0 || minDimension > 20) {
            throw new IllegalArgumentException("minDimension 须在 0~20: " + minDimension);
        }
        verified = List.copyOf(Objects.requireNonNull(verified, "verified"));
        // warnings 可空（旧产物/reviewer 手写产物缺键）→ 归一化为空表；防御 null 条目
        warnings = warnings == null ? List.of() : warnings.stream().filter(Objects::nonNull).toList();
        Objects.requireNonNull(passedAt, "passedAt");
    }

    /** 便捷构造（v4 原签名）：无软提示——既有调用方与测试零改动 */
    public ReviewPassed(int total, int minDimension, List<String> verified, String passedAt) {
        this(SCHEMA_VERSION, total, minDimension, verified, List.of(), passedAt);
    }

    /** 便捷构造：携带软提示（R1 PASS_WITH_WARNINGS 交付场景） */
    public ReviewPassed(int total, int minDimension, List<String> verified,
                        List<ReviewWarning> warnings, String passedAt) {
        this(SCHEMA_VERSION, total, minDimension, verified, warnings, passedAt);
    }

    /**
     * 软提示（R1 质检分级）：SOFT 违例的交付载体，随 review_passed.json 落盘，
     * 前端质检明细折叠区逐条渲染（仿 TravelPlanner 逐项 pass/fail 清单）。
     */
    public record ReviewWarning(
            String rule,
            String detail,
            String suggestion) {

        public ReviewWarning {
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(detail, "detail");
            // suggestion 可空（并非每条规则都有可执行建议）
        }
    }
}
