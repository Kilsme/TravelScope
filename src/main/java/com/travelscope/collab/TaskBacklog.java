package com.travelscope.collab;

import java.util.List;
import java.util.Objects;

/**
 * 工作区契约：task_backlog.json（schemaVersion=4）
 * <p>
 * 生产者：TaskBacklogPlanner（代码模板拆任务）；消费者：planning-agent。
 * 对应 v3 的 task_backlog.md，字段对齐 {@code TaskRegistry.PlanningTask} + 运行时状态。
 * </p>
 * <p>
 * schemaVersion 恒为 {@link #SCHEMA_VERSION}（紧凑构造器强校验）；JSON 键与组件同名。
 * </p>
 */
public record TaskBacklog(
        int schemaVersion,
        String sessionId,
        List<TaskItem> tasks) {

    /** 契约版本 */
    public static final int SCHEMA_VERSION = 4;

    public TaskBacklog {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "schemaVersion 必须为 " + SCHEMA_VERSION + "，实际: " + schemaVersion);
        }
        Objects.requireNonNull(sessionId, "sessionId");
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks"));
    }

    /** 便捷构造：schemaVersion 固定为 {@link #SCHEMA_VERSION} */
    public TaskBacklog(String sessionId, List<TaskItem> tasks) {
        this(SCHEMA_VERSION, sessionId, tasks);
    }

    /**
     * 单条任务。status 取值 PENDING / IN_PROGRESS / DONE / FAILED（沿用 TaskRegistry 状态语义）；
     * priority 取值 P0 / P1 / P2；suggestedTool 可空（建议工具/技能提示，允许缺省）。
     */
    public record TaskItem(
            String taskId,
            String description,
            String suggestedTool,
            String priority,
            String status,
            String updatedAt) {

        public TaskItem {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(priority, "priority");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }
}
