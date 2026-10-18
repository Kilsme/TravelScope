package com.travelscope.service.workflow;

import com.travelscope.collab.CollabFileReader;
import com.travelscope.collab.CollabFileWriter;
import com.travelscope.collab.IntakeResult;
import com.travelscope.collab.TaskBacklog;
import com.travelscope.collab.TaskWorkspace;
import com.travelscope.service.TaskRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TaskBacklogPlanner（S2.4 代码模板任务拆分器）测试。
 * 真实链路：@TempDir 作 workspace 根 + 真实 TaskWorkspace/TaskRegistry，零 mock——
 * 过四维校验 ⇔ registry.hasBacklog 为真（校验不过 createBacklog 不注册容器）。
 */
class TaskBacklogPlannerTest {

    @TempDir
    Path tempDir;

    private TaskWorkspace workspace;
    private TaskRegistry registry;
    private TaskBacklogPlanner planner;

    @BeforeEach
    void setUp() {
        workspace = new TaskWorkspace(new CollabFileWriter(tempDir.toString()), new CollabFileReader());
        registry = new TaskRegistry(workspace);
        planner = new TaskBacklogPlanner(registry, workspace);
    }

    // ---------- 用例 1：跨城全字段 → T1~T5 固定生成，过四维校验并落盘 ----------
    @Test
    void crossCityFullFieldsGeneratesFiveTasksAndPersists() {
        IntakeResult intake = intake("conv-1", IntakeResult.Status.DONE,
                "上海", "杭州", "2", "2026-10-24", "2", "想看西湖", null, null);

        TaskBacklog backlog = planner.plan("u1", intake);

        assertThat(backlog.sessionId()).isEqualTo("conv-1");
        assertThat(backlog.tasks()).hasSize(5);
        assertThat(backlog.tasks()).extracting(TaskBacklog.TaskItem::taskId)
                .containsExactly("T1", "T2", "T3", "T4", "T5");
        assertThat(backlog.tasks()).extracting(TaskBacklog.TaskItem::priority)
                .containsExactly("P0", "P0", "P0", "P1", "P1");
        assertThat(backlog.tasks()).allSatisfy(t -> assertThat(t.status()).isEqualTo("PENDING"));
        TaskBacklog.TaskItem t1 = backlog.tasks().get(0);
        assertThat(t1.suggestedTool()).isEqualTo("train-ticket-query");
        assertThat(registry.hasBacklog("u1", "conv-1")).as("四维校验通过，内存容器已注册").isTrue();
        // 返回值与落盘内容一致
        assertThat(workspace.readTaskBacklog("u1", "conv-1")).hasValue(backlog);
        assertThat(tempDir.resolve("u1/tasks/conv-1/task_backlog.json")).exists();
    }

    // ---------- 用例 2：描述含目的地与天数（intake 字段模板填充） ----------
    @Test
    void descriptionsContainDestinationAndDays() {
        TaskBacklog backlog = planner.plan("u1", intake("conv-2", IntakeResult.Status.DONE,
                "上海", "杭州", "2", "2026-10-24", null, null, null, null));

        assertThat(backlog.tasks().get(0).description())
                .contains("上海").contains("杭州").contains("2026-10-24");
        assertThat(backlog.tasks().get(1).description()).contains("杭州").contains("2");
        assertThat(backlog.tasks().get(2).description()).contains("杭州").contains("2");
        assertThat(backlog.tasks().get(4).description()).contains("杭州").contains("2");
    }

    // ---------- 用例 3：无 fromCity → T1 降级为市内交通任务（清单仍过四维） ----------
    @Test
    void nullFromCityDegradesT1ToCityTransport() {
        TaskBacklog backlog = planner.plan("u1", intake("conv-3", IntakeResult.Status.DONE,
                null, "杭州", "2", "2026-10-24", null, null, null, null));

        TaskBacklog.TaskItem t1 = backlog.tasks().get(0);
        assertThat(t1.description()).contains("市内交通");
        assertThat(t1.suggestedTool()).isNull();
        assertThat(registry.hasBacklog("u1", "conv-3")).as("降级后清单仍过四维校验").isTrue();
    }

    // ---------- 用例 4：fromCity 与目的地同城（DEGRADED 默认同城场景）→ T1 同样降级 ----------
    @Test
    void sameCityFromCityAlsoDegradesT1() {
        TaskBacklog backlog = planner.plan("u1", intake("conv-4", IntakeResult.Status.DEGRADED,
                "杭州", "杭州", "2", "2026-10-24", null, null, null, null));

        TaskBacklog.TaskItem t1 = backlog.tasks().get(0);
        assertThat(t1.description()).contains("市内交通");
        assertThat(t1.suggestedTool()).isNull();
        assertThat(registry.hasBacklog("u1", "conv-4")).isTrue();
    }

    // ---------- 用例 5：偏好含机票关键词 → T1 升级 flight-ticket-query（仍过四维） ----------
    @Test
    void flightKeywordUpgradesT1Tool() {
        TaskBacklog backlog = planner.plan("u1", intake("conv-5", IntakeResult.Status.DONE,
                "上海", "杭州", "2", "2026-10-24", null, "想坐飞机", null, null));

        TaskBacklog.TaskItem t1 = backlog.tasks().get(0);
        assertThat(t1.suggestedTool()).isEqualTo("flight-ticket-query");
        assertThat(t1.description()).contains("机票");
        assertThat(registry.hasBacklog("u1", "conv-5")).as("升级后清单仍过四维校验").isTrue();
    }

    // ---------- 用例 6：偏好原文嵌入 T3 描述 ----------
    @Test
    void preferenceVerbatimEmbeddedInT3() {
        TaskBacklog backlog = planner.plan("u1", intake("conv-6", IntakeResult.Status.DONE,
                "上海", "杭州", "2", "2026-10-24", null, "想逛博物馆、吃杭帮菜", null, null));

        assertThat(backlog.tasks().get(2).description()).contains("想逛博物馆、吃杭帮菜");
    }

    // ---------- 用例 7：未收齐（COLLECTING）→ 快速失败，不登记 ----------
    @Test
    void collectingStatusRejected() {
        IntakeResult collecting = intake("conv-7", IntakeResult.Status.COLLECTING,
                null, "杭州", null, null, null, null, null, null);

        assertThatThrownBy(() -> planner.plan("u1", collecting))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("COLLECTING");
        assertThat(registry.hasBacklog("u1", "conv-7")).as("未登记任务清单").isFalse();
    }

    // ==================== 测试数据构造 ====================

    /** 组装 IntakeResult（RequirementFields 顺序：destination/days/startDate/fromCity/budget/preference/people/special） */
    private static IntakeResult intake(String sessionId, IntakeResult.Status status,
                                       String from, String dest, String days, String date,
                                       String people, String pref, String special, String budget) {
        return new IntakeResult(sessionId, status, 0,
                new IntakeResult.RequirementFields(
                        fv(dest), fv(days), fv(date), fv(from),
                        fv(budget), fv(pref), fv(people), fv(special)),
                status == IntakeResult.Status.DEGRADED, "2026-10-18 10:00:00");
    }

    private static IntakeResult.FieldValue fv(String value) {
        return new IntakeResult.FieldValue(value, value != null, null);
    }
}
