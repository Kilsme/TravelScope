package com.travelscope.collab;

import java.util.List;
import java.util.Objects;

/**
 * 工作区契约：execution_result.json（schemaVersion=4）
 * <p>
 * 生产者：planning-agent（每完成一项任务追加一条）；消费者：travel-master / 审计排查。
 * 对应 v3 的 execution_result.md。
 * </p>
 * <p>
 * schemaVersion 恒为 {@link #SCHEMA_VERSION}（紧凑构造器强校验）；JSON 键与组件同名。
 * </p>
 */
public record ExecutionResult(
        int schemaVersion,
        String sessionId,
        List<TaskResultItem> results) {

    /** 契约版本 */
    public static final int SCHEMA_VERSION = 4;

    public ExecutionResult {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "schemaVersion 必须为 " + SCHEMA_VERSION + "，实际: " + schemaVersion);
        }
        Objects.requireNonNull(sessionId, "sessionId");
        results = List.copyOf(Objects.requireNonNull(results, "results"));
    }

    /** 便捷构造：schemaVersion 固定为 {@link #SCHEMA_VERSION} */
    public ExecutionResult(String sessionId, List<TaskResultItem> results) {
        this(SCHEMA_VERSION, sessionId, results);
    }

    /**
     * 单任务执行结果。status 取值 DONE / FAILED；summary 为一句话结论；
     * toolEvidence 可空（关键工具返回摘要，如车次/票价/评分）；finishedAt 可空。
     */
    public record TaskResultItem(
            String taskId,
            String status,
            String summary,
            List<String> toolEvidence,
            String finishedAt) {

        public TaskResultItem {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(summary, "summary");
            toolEvidence = toolEvidence == null ? null : List.copyOf(toolEvidence);
        }
    }
}
