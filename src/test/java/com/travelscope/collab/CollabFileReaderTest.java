package com.travelscope.collab;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S0.4 验证：CollabFileReader 的「缺省 empty / 坏文件含路径抛出 / readOrThrow」语义。
 */
class CollabFileReaderTest {

    @TempDir
    Path tempDir;

    private CollabFileReader reader;
    private CollabFileWriter writer;

    @BeforeEach
    void setUp() {
        reader = new CollabFileReader();
        writer = new CollabFileWriter(tempDir.toString());
    }

    @Test
    void missingFileReturnsEmpty() {
        Optional<TaskBacklog> result = reader.read(tempDir, "task_backlog.json", TaskBacklog.class);
        assertThat(result).isEmpty();
    }

    @Test
    void writeThenReadRoundTrip() {
        TaskBacklog backlog = new TaskBacklog("conv-1", List.of(
                new TaskBacklog.TaskItem("T1", "火车票", null, "P0", "PENDING", "2026-10-15 21:30:00")));
        writer.write(tempDir, "task_backlog.json", backlog);

        Optional<TaskBacklog> back = reader.read(tempDir, "task_backlog.json", TaskBacklog.class);
        assertThat(back).isPresent().contains(backlog);
    }

    @Test
    void corruptedFileThrowsWithAbsolutePath() throws Exception {
        // 坏文件：JSON 截断（缺右括号）
        Files.writeString(tempDir.resolve("task_backlog.json"),
                "{\"schemaVersion\":4,\"sessionId\":\"conv-1\",\"tasks\":[");

        assertThatThrownBy(() -> reader.read(tempDir, "task_backlog.json", TaskBacklog.class))
                .isInstanceOf(CollabValidationException.class)
                .hasMessageContaining(tempDir.resolve("task_backlog.json").toString());
    }

    @Test
    void readOrThrowMissingFileThrows() {
        assertThatThrownBy(() -> reader.readOrThrow(tempDir, "task_backlog.json", TaskBacklog.class))
                .isInstanceOf(CollabValidationException.class)
                .hasMessageContaining("缺失")
                .hasMessageContaining("task_backlog.json");
    }
}
