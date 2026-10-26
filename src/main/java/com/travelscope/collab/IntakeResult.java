package com.travelscope.collab;

import java.util.Objects;

/**
 * 工作区契约：intake_result.json（schemaVersion=4）
 * <p>
 * 生产者：RequirementWorkflowService（需求采集 workflow）；消费者：planning-agent / travel-master。
 * 对应 v3 的 intake_done.md，字段语义对齐 {@code TripRequirementState}。
 * </p>
 * <p>
 * schemaVersion 恒为 {@link #SCHEMA_VERSION}：紧凑构造器强校验，旧版本文件反序列化即构造失败
 * （由 S0.2 校验层呈现为「版本不符」）；新建实例使用省略该参的便捷构造器。
 * JSON 键与 record 组件同名（camelCase），fastjson2 默认映射。
 * </p>
 */
public record IntakeResult(
        int schemaVersion,
        String sessionId,
        Status status,
        int clarifyCycles,
        RequirementFields fields,
        boolean degraded,
        String collectedAt) {

    /** 契约版本（v4 起步为 4，未来演进可双读） */
    public static final int SCHEMA_VERSION = 4;

    public IntakeResult {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "schemaVersion 必须为 " + SCHEMA_VERSION + "，实际: " + schemaVersion);
        }
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(status, "status");
        if (clarifyCycles < 0) {
            throw new IllegalArgumentException("clarifyCycles 不能为负: " + clarifyCycles);
        }
        Objects.requireNonNull(fields, "fields");
        Objects.requireNonNull(collectedAt, "collectedAt");
    }

    /** 便捷构造：schemaVersion 固定为 {@link #SCHEMA_VERSION} */
    public IntakeResult(String sessionId, Status status, int clarifyCycles,
                        RequirementFields fields, boolean degraded, String collectedAt) {
        this(SCHEMA_VERSION, sessionId, status, clarifyCycles, fields, degraded, collectedAt);
    }

    /** 需求收集状态（对齐 TripRequirementState.Status） */
    public enum Status {
        /** 收集中（仍有必填缺项） */
        COLLECTING,
        /** 必填项已收齐 */
        DONE,
        /** 反问超限后带默认值放行（方案中需标注「待确认」） */
        DEGRADED
    }

    /**
     * 8 个需求字段（对齐 TripRequirementState：必填 destination/days/startDate/fromCity，
     * 选填 budget/preference/people/special）；未收集到的字段为 null。
     */
    public record RequirementFields(
            FieldValue destination,
            FieldValue days,
            FieldValue startDate,
            FieldValue fromCity,
            FieldValue budget,
            FieldValue preference,
            FieldValue people,
            FieldValue special) {
    }

    /**
     * 单字段值。value 为 null 表示未收集；confirmed=false 表示默认值/待确认；
     * note 可空，记录默认值来源或待确认说明。
     * value 统一 String（与 TripRequirementStore Redis hash 的文本语义一致，数字由消费方解析）。
     */
    public record FieldValue(String value, boolean confirmed, String note) {

        public FieldValue(String value, boolean confirmed) {
            this(value, confirmed, null);
        }
    }
}
