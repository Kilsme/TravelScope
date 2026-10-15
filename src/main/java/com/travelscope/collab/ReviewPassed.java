package com.travelscope.collab;

import java.util.List;
import java.util.Objects;

/**
 * 工作区契约：review_passed.json（schemaVersion=4）
 * <p>
 * 生产者：reviewer-agent（质检通过时）；消费者：ChatService（渲染「已通过质量审阅」标注）。
 * 对应 v3 的 review_passed.md；本文件存在即视为通过（ReviewerRetryMiddleware 据此重置回炉计数）。
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
        Objects.requireNonNull(passedAt, "passedAt");
    }

    /** 便捷构造：schemaVersion 固定为 {@link #SCHEMA_VERSION} */
    public ReviewPassed(int total, int minDimension, List<String> verified, String passedAt) {
        this(SCHEMA_VERSION, total, minDimension, verified, passedAt);
    }
}
