package com.travelscope.service;

import com.travelscope.collab.CollabFileReader;
import com.travelscope.collab.CollabFileWriter;
import com.travelscope.collab.TaskBacklog;
import com.travelscope.collab.TaskWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S0.6 验证：TaskRegistry 存储 JSON 化。
 * createBacklog 落 task_backlog.json（四维校验仍把门）/ updateStatus 后 JSON 状态翻转 /
 * 非法更新不改写 JSON。
 */
class TaskRegistryJsonTest {

    @TempDir
    Path tempDir;

    private TaskWorkspace workspace;
    private TaskRegistry registry;

    @BeforeEach
    void setUp() {
        workspace = new TaskWorkspace(new CollabFileWriter(tempDir.toString()), new CollabFileReader());
        registry = new TaskRegistry(workspace);
    }

    /** 四维覆盖的合法清单（交通/住宿/景点/天气各一项） */
    private static String fourDimensionTasksJson() {
        return """
                [
                  {"taskId":"T1","description":"查询上海到杭州火车票","suggestedTool":"train-ticket-query","priority":"P0"},
                  {"taskId":"T2","description":"查询杭州两日游天气","suggestedTool":"weather-query","priority":"P0"},
                  {"taskId":"T3","description":"检索杭州景点候选","suggestedTool":"attraction-search","priority":"P0"},
                  {"taskId":"T4","description":"搜索杭州西湖附近酒店","suggestedTool":"hotel-search","priority":"P1"}
                ]
                """;
    }

    @Test
    void createBacklogWritesReadableJson() {
        String result = registry.createBacklog("u1", "conv-1", fourDimensionTasksJson());

        assertThat(result).contains("任务清单已登记").contains("4 项任务");
        Optional<TaskBacklog> back = workspace.readTaskBacklog("u1", "conv-1");
        assertThat(back).isPresent();
        assertThat(back.get().schemaVersion()).isEqualTo(4);
        assertThat(back.get().tasks()).hasSize(4);
        assertThat(back.get().tasks()).allSatisfy(t -> {
            assertThat(t.status()).isEqualTo("PENDING");
            assertThat(t.updatedAt()).isNotBlank();
        });
        assertThat(back.get().tasks().get(0).taskId()).isEqualTo("T1");
    }

    @Test
    void missingDimensionRejectedAndNothingPersisted() {
        // 缺天气维
        String json = """
                [
                  {"taskId":"T1","description":"查询火车票","suggestedTool":"train-ticket-query","priority":"P0"},
                  {"taskId":"T2","description":"搜索酒店","suggestedTool":"hotel-search","priority":"P1"},
                  {"taskId":"T3","description":"检索景点","suggestedTool":"attraction-search","priority":"P0"}
                ]
                """;
        String result = registry.createBacklog("u1", "conv-2", json);

        assertThat(result).startsWith("ERROR").contains("天气");
        assertThat(workspace.readTaskBacklog("u1", "conv-2"))
                .as("四维校验不过不得落盘")
                .isEmpty();
        assertThat(workspace.getTaskDir("u1", "conv-2").resolve("task_backlog.json")).doesNotExist();
    }

    @Test
    void updateStatusFlipsJsonStatus() {
        registry.createBacklog("u1", "conv-1", fourDimensionTasksJson());

        String result = registry.updateStatus("u1", "conv-1", "T1", "IN_PROGRESS");
        assertThat(result).contains("已更新 T1 → IN_PROGRESS");

        TaskBacklog back = workspace.readTaskBacklog("u1", "conv-1").orElseThrow();
        TaskBacklog.TaskItem t1 = back.tasks().stream()
                .filter(t -> t.taskId().equals("T1")).findFirst().orElseThrow();
        assertThat(t1.status()).isEqualTo("IN_PROGRESS");
        // 其余任务保持 PENDING
        assertThat(back.tasks().stream()
                .filter(t -> !t.taskId().equals("T1")))
                .allSatisfy(t -> assertThat(t.status()).isEqualTo("PENDING"));
    }

    @Test
    void invalidUpdateDoesNotRewriteJson() throws Exception {
        registry.createBacklog("u1", "conv-1", fourDimensionTasksJson());
        String before = Files.readString(
                workspace.getTaskDir("u1", "conv-1").resolve("task_backlog.json"));

        String badStatus = registry.updateStatus("u1", "conv-1", "T1", "EXPLODED");
        assertThat(badStatus).startsWith("ERROR");
        String unknownTask = registry.updateStatus("u1", "conv-1", "T99", "DONE");
        assertThat(unknownTask).startsWith("ERROR");

        String after = Files.readString(
                workspace.getTaskDir("u1", "conv-1").resolve("task_backlog.json"));
        assertThat(after).as("非法更新不得改写磁盘契约").isEqualTo(before);
    }
}
