package com.travelscope.collab;

import java.util.List;
import java.util.Objects;

/**
 * 工作区契约：session_meta.json（schemaVersion=4）
 * <p>
 * 生产者：TaskWorkspace（initTaskWorkspace 时创建）；消费者：运维/审计排查。
 * 对应 v3 的 session_meta.md。
 * </p>
 * <p>
 * schemaVersion 恒为 {@link #SCHEMA_VERSION}（紧凑构造器强校验）；JSON 键与组件同名。
 * </p>
 */
public record SessionMeta(
        int schemaVersion,
        String userId,
        String sessionId,
        String createdAt,
        String status,
        List<String> participants,
        String updatedAt) {

    /** 契约版本 */
    public static final int SCHEMA_VERSION = 4;

    public SessionMeta {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "schemaVersion 必须为 " + SCHEMA_VERSION + "，实际: " + schemaVersion);
        }
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(status, "status");
        participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
        // updatedAt 可空（未更新过）
    }

    /** 便捷构造：schemaVersion 固定为 {@link #SCHEMA_VERSION} */
    public SessionMeta(String userId, String sessionId, String createdAt, String status,
                       List<String> participants, String updatedAt) {
        this(SCHEMA_VERSION, userId, sessionId, createdAt, status, participants, updatedAt);
    }
}
