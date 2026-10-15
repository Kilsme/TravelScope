package com.travelscope.collab;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S0.3 验证：CollabFileWriter 的「先校验、原子写」语义。
 * 覆盖：非法内容拒写且目标文件不被污染 / 无 tmp 残留 / 文件名-类型不匹配拒 /
 * 目录自动创建 / 写后文件可过校验回读 / 会话目录解析。
 */
class CollabFileWriterTest {

    @TempDir
    Path tempDir;

    private CollabFileWriter writer;

    @BeforeEach
    void setUp() {
        writer = new CollabFileWriter(tempDir.toString());
    }

    private static TaskBacklog validBacklog() {
        return new TaskBacklog("conv-1", List.of(
                new TaskBacklog.TaskItem("T1", "火车票", null, "P0", "PENDING", "2026-10-15T21:30:00")));
    }

    @Test
    void rejectsInvalidSchemaLeavesFileUntouched() throws Exception {
        writer.write(tempDir, "task_backlog.json", validBacklog());
        String original = Files.readString(tempDir.resolve("task_backlog.json"));

        // 重复 taskId：DTO 构造器不拦（跨字段规则），必须由 Schema 校验拒
        TaskBacklog duplicate = new TaskBacklog("conv-1", List.of(
                new TaskBacklog.TaskItem("T1", "火车票", null, "P0", "PENDING", "t"),
                new TaskBacklog.TaskItem("T1", "天气", null, "P0", "PENDING", "t")));
        assertThatThrownBy(() -> writer.write(tempDir, "task_backlog.json", duplicate))
                .isInstanceOf(CollabValidationException.class)
                .hasMessageContaining("重复");

        assertThat(Files.readString(tempDir.resolve("task_backlog.json")))
                .as("校验失败不得污染目标文件")
                .isEqualTo(original);
    }

    @Test
    void atomicWriteLeavesNoTmpResidue() throws Exception {
        writer.write(tempDir, "task_backlog.json", validBacklog());
        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files.filter(p -> p.getFileName().toString().endsWith(".tmp")))
                    .as("原子写完成后不得残留 tmp 文件")
                    .isEmpty();
        }
        assertThat(tempDir.resolve("task_backlog.json")).exists();
    }

    @Test
    void mismatchedFileNameRejected() {
        // conv-97 事故场景：backlog 内容试图落进 draft 文件
        assertThatThrownBy(() -> writer.write(tempDir, "itinerary_draft.json", validBacklog()))
                .isInstanceOf(CollabValidationException.class)
                .hasMessageContaining("类型不匹配")
                .hasMessageContaining("itinerary_draft.json");
        assertThat(tempDir.resolve("itinerary_draft.json")).doesNotExist();
    }

    @Test
    void createsMissingDirectories() {
        Path nested = tempDir.resolve("u1/tasks/conv-1");
        writer.write(nested, "task_backlog.json", validBacklog());
        assertThat(nested.resolve("task_backlog.json")).exists();
    }

    @Test
    void writtenFilePassesSchemaValidationOnReadBack() throws Exception {
        writer.write(tempDir, "route_plan.json", new RoutePlan(List.of(),
                new RoutePlan.SolverMeta("NN+2opt", "amap-transit", 5, List.of())));
        RoutePlan back = CollabSchemaValidator.parse(
                Files.readString(tempDir.resolve("route_plan.json")), RoutePlan.class);
        assertThat(back.solverMeta().algorithm()).isEqualTo("NN+2opt");
        assertThat(back.schemaVersion()).isEqualTo(4);
    }

    @Test
    void resolveSessionDirFollowsWorkspaceLayout() {
        Path dir = writer.resolveSessionDir("u1", "conv-1");
        assertThat(dir).isEqualTo(tempDir.resolve("u1/tasks/conv-1"));
    }
}
