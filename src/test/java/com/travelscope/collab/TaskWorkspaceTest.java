package com.travelscope.collab;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S0.5 验证：TaskWorkspace 迁出后的 JSON 类型化读写与过渡兼容。
 * 覆盖：init 建目录 + session_meta.json 合法 / 双会话隔离 / 旧 .md 忽略 /
 * appendExecutionResult 累积 / updateSessionStatus / 类型化往返。
 */
class TaskWorkspaceTest {

    @TempDir
    Path tempDir;

    private TaskWorkspace workspace;

    @BeforeEach
    void setUp() {
        workspace = new TaskWorkspace(
                new CollabFileWriter(tempDir.toString()), new CollabFileReader());
    }

    @Test
    void initCreatesDirAndValidSessionMeta() {
        Path dir = workspace.initTaskWorkspace("u1", "conv-1");

        assertThat(dir).exists().isDirectory();
        Optional<SessionMeta> meta = workspace.readSessionMeta("u1", "conv-1");
        assertThat(meta).isPresent();
        assertThat(meta.get().schemaVersion()).isEqualTo(4);
        assertThat(meta.get().status()).isEqualTo("进行中");
        assertThat(meta.get().participants())
                .containsExactly("travel-master", "planning-agent", "reviewer-agent");
    }

    @Test
    void sessionAndUserIsolation() {
        TaskBacklog backlog = new TaskBacklog("conv-1", List.of(
                new TaskBacklog.TaskItem("T1", "火车票", null, "P0", "PENDING", "2026-10-15 21:30:00")));
        workspace.writeTaskBacklog("userA", "conv-1", backlog);

        assertThat(workspace.readTaskBacklog("userA", "conv-1")).contains(backlog);
        assertThat(workspace.readTaskBacklog("userA", "conv-2"))
                .as("同用户不同会话互不可见").isEmpty();
        assertThat(workspace.readTaskBacklog("userB", "conv-1"))
                .as("不同用户同会话互不可见").isEmpty();
    }

    @Test
    void legacyMdFileIsIgnored() throws Exception {
        // v3 残留：目录里只有旧格式 task_backlog.md
        Path dir = workspace.getTaskDir("u1", "conv-1");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("task_backlog.md"), "# 任务清单（v3 旧格式）");

        assertThat(workspace.readTaskBacklog("u1", "conv-1"))
                .as("旧 MD 不迁移、不消费，返回 empty")
                .isEmpty();
    }

    @Test
    void appendExecutionResultAccumulates() {
        workspace.appendExecutionResult("u1", "conv-1",
                new ExecutionResult.TaskResultItem("T1", "DONE", "G7331 08:00", null, "2026-10-15 21:31:00"));
        workspace.appendExecutionResult("u1", "conv-1",
                new ExecutionResult.TaskResultItem("T2", "DONE", "杭州晴 18-25℃", null, "2026-10-15 21:32:00"));

        Optional<ExecutionResult> result = workspace.readExecutionResult("u1", "conv-1");
        assertThat(result).isPresent();
        assertThat(result.get().results()).hasSize(2);
        assertThat(result.get().results().get(0).taskId()).isEqualTo("T1");
        assertThat(result.get().results().get(1).taskId()).isEqualTo("T2");
    }

    @Test
    void updateSessionStatusFlipsStatusAndStampsUpdatedAt() {
        workspace.initTaskWorkspace("u1", "conv-1");
        workspace.updateSessionStatus("u1", "conv-1", "已完成");

        SessionMeta meta = workspace.readSessionMeta("u1", "conv-1").orElseThrow();
        assertThat(meta.status()).isEqualTo("已完成");
        assertThat(meta.updatedAt()).isNotNull();
    }

    @Test
    void routePlanTypedRoundTrip() {
        RoutePlan plan = new RoutePlan(List.of(
                new RoutePlan.RouteDay(1, "2026-10-25", List.of(
                        new RoutePlan.RoutePoi("西湖", 120.15, 30.24, "09:00", "12:00", null, null),
                        new RoutePlan.RoutePoi("灵隐寺", 120.10, 30.24, "13:30", "16:00", "公交", 45)),
                        45)),
                new RoutePlan.SolverMeta("NN+2opt", "amap-transit", 12, List.of()));

        workspace.writeRoutePlan("u1", "conv-1", plan);
        assertThat(workspace.readRoutePlan("u1", "conv-1")).contains(plan);
    }
}
