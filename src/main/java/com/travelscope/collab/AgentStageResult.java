package com.travelscope.collab;

import java.util.List;
import java.util.Objects;

/** 子 Agent 阶段的最小机器可读终态契约。 */
public record AgentStageResult(
        int schemaVersion,
        Stage stage,
        Status status,
        String reasonCode,
        boolean retryable,
        List<String> artifacts,
        String completedAt) {

    public static final int SCHEMA_VERSION = 4;

    public enum Stage { PLANNER, REVIEWER }
    public enum Status { DONE, FAILED, NOT_READY }

    public AgentStageResult {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion 必须为 " + SCHEMA_VERSION + "，实际: " + schemaVersion);
        }
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(status, "status");
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        Objects.requireNonNull(completedAt, "completedAt");
        if (status == Status.DONE && artifacts.isEmpty()) {
            throw new IllegalArgumentException("DONE 状态必须声明至少一个产物");
        }
        if (status != Status.DONE && (reasonCode == null || reasonCode.isBlank())) {
            throw new IllegalArgumentException(status + " 状态必须提供 reasonCode");
        }
    }

    public AgentStageResult(Stage stage, Status status, String reasonCode,
                            boolean retryable, List<String> artifacts, String completedAt) {
        this(SCHEMA_VERSION, stage, status, reasonCode, retryable, artifacts, completedAt);
    }
}
