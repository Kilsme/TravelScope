package com.travelscope.collab;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S0.8 验证：覆写防护与路径隔离（P0 收尾）。
 * 机制性断言「单一生产者 + 校验拒写 + 坏文件显式抛出」——conv-97 事故
 * （itinerary_draft/route_plan/poi_shortlist 被写成 task_backlog 内容）在
 * 契约层不再可能发生。
 */
class CollabSafetyTest {

    @TempDir
    Path tempDir;

    private TaskWorkspace workspace;
    private CollabFileWriter writer;

    @BeforeEach
    void setUp() {
        writer = new CollabFileWriter(tempDir.toString());
        workspace = new TaskWorkspace(writer, new CollabFileReader());
    }

    private static TaskBacklog validBacklog() {
        return new TaskBacklog("conv-1", List.of(
                new TaskBacklog.TaskItem("T1", "查询火车票", "train-ticket-query",
                        "P0", "PENDING", "2026-10-15 21:30:00")));
    }

    @Test
    void conv97AccidentCannotRecur() {
        // conv-97 事故复盘：backlog 内容被写进 itinerary_draft / route_plan / poi_shortlist
        Path dir = workspace.getTaskDir("u1", "conv-1");
        for (String victim : List.of("itinerary_draft.json", "route_plan.json", "poi_shortlist.json")) {
            assertThatThrownBy(() -> writer.write(dir, victim, validBacklog()))
                    .as("backlog 内容写入 %s 必须被拒", victim)
                    .isInstanceOf(CollabValidationException.class)
                    .hasMessageContaining("类型不匹配")
                    .hasMessageContaining(victim);
            assertThat(dir.resolve(victim)).as("被拒后目标文件不得产生: %s", victim).doesNotExist();
        }
    }

    @Test
    void userSessionPathIsolation() {
        workspace.writeTaskBacklog("userA", "conv-1", validBacklog());

        assertThat(workspace.readTaskBacklog("userB", "conv-1"))
                .as("横向跨用户不可见").isEmpty();
        assertThat(workspace.readTaskBacklog("userA", "conv-2"))
                .as("纵向跨会话不可见").isEmpty();
        assertThat(workspace.readTaskBacklog("userA", "conv-1"))
                .as("本用户本会话可读回").isPresent();
    }

    @Test
    void corruptedRoutePlanSurfacedNotSilentlyIgnored() throws Exception {
        RoutePlan plan = new RoutePlan(List.of(
                new RoutePlan.RouteDay(1, "2026-10-25", List.of(
                        new RoutePlan.RoutePoi("西湖", 120.15, 30.24, "09:00", "12:00", null, null)),
                        0)),
                new RoutePlan.SolverMeta("NN+2opt", "amap-transit", 5, List.of()));
        workspace.writeRoutePlan("u1", "conv-1", plan);

        // 手工改坏（截断 JSON）
        Path file = workspace.getTaskDir("u1", "conv-1").resolve("route_plan.json");
        String valid = Files.readString(file);
        Files.writeString(file, valid.substring(0, valid.length() / 2));

        // 读方必须显式抛出（含绝对路径），而非静默返回 empty 或半个坏对象
        assertThatThrownBy(() -> workspace.readRoutePlan("u1", "conv-1"))
                .isInstanceOf(CollabValidationException.class)
                .hasMessageContaining(file.toString());
    }

    @Test
    void legacyVersionFileRejected() throws Exception {
        // v3 时代格式（schemaVersion=3）残留：读取必须拒为「版本不符」
        Path dir = workspace.getTaskDir("u1", "conv-1");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("intake_result.json"), """
                {"schemaVersion":3,"sessionId":"conv-1","status":"DONE","clarifyCycles":0,
                 "fields":{},"degraded":false,"collectedAt":"2026-10-15 21:30:00"}
                """);

        assertThatThrownBy(() -> workspace.readIntakeResult("u1", "conv-1"))
                .isInstanceOf(CollabValidationException.class)
                .hasMessageContaining("版本不符");
    }
}
