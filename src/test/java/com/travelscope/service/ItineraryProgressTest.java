package com.travelscope.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.travelscope.collab.CollabFileReader;
import com.travelscope.collab.CollabFileWriter;
import com.travelscope.collab.ItineraryDraft;
import com.travelscope.collab.RoutePlan;
import com.travelscope.collab.TaskWorkspace;
import com.travelscope.dto.ChatEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R3② 流式行程进度（ItineraryProgress）单测：骨架/逐天事件构建、幂等差量与版本递增。
 * TaskWorkspace 真实（@TempDir 落盘产物），纯 Java 无 LLM 依赖。
 */
class ItineraryProgressTest {

    @TempDir
    Path tempDir;

    private TaskWorkspace workspace;
    private ItineraryProgress progress;

    @BeforeEach
    void setUp() {
        workspace = new TaskWorkspace(new CollabFileWriter(tempDir.toString()), new CollabFileReader());
        progress = new ItineraryProgress();
    }

    private static RoutePlan routePlan(String... poiNames) {
        return new RoutePlan(List.of(new RoutePlan.RouteDay(1, null, List.of(
                new RoutePlan.RoutePoi(poiNames[0], 120.1, 30.2, "09:00", "11:00", null, null),
                new RoutePlan.RoutePoi(poiNames[1], 120.2, 30.3, "13:00", "15:00", "公交", 25)), 25)),
                new RoutePlan.SolverMeta("NN+2opt", "amap-transit", 5, List.of()));
    }

    private static String markdown(String day1, String day2) {
        return "# 行程方案（2 天）\n\n"
                + "## Day 1（2026-10-24）园林\n\n" + day1 + "\n\n"
                + "## Day 2（2026-10-25）古迹\n\n" + day2 + "\n\n"
                + "## 大交通\n\nG7331 上海虹桥→苏州\n\n## 预算汇总\n\n- 总预算：1200（CNY）\n";
    }

    private void seed(RoutePlan plan, String markdownBody) {
        workspace.writeRoutePlan("u1", "conv-p", plan);
        workspace.writeItineraryDraft("u1", "conv-p", new ItineraryDraft(
                List.of(new ItineraryDraft.DraftDay(1, "2026-10-24", "园林", List.of()),
                        new ItineraryDraft.DraftDay(2, "2026-10-25", "古迹", List.of())),
                null, List.of(), List.of(), markdownBody));
    }

    @Test
    @DisplayName("splitDaySections：按「## Day N」切分，天节含标题，非天节（大交通/预算）不入")
    void splitDaySections() {
        Map<Integer, String> sections = ItineraryProgress.splitDaySections(markdown("D1 内容", "D2 内容"));

        assertThat(sections).containsOnlyKeys(1, 2);
        assertThat(sections.get(1)).startsWith("## Day 1").contains("D1 内容").doesNotContain("D2 内容");
        assertThat(sections.get(2)).startsWith("## Day 2").contains("D2 内容");
        // 非天节不入（随 done 全文交付）
        assertThat(sections.values()).noneSatisfy(s -> assertThat(s).contains("大交通"));
    }

    @Test
    @DisplayName("首推：骨架 + 全部天 v1；内容未变重推为空（幂等）")
    void firstEmitThenIdempotent() {
        seed(routePlan("拙政园", "平江路"), markdown("D1 v1", "D2 v1"));

        List<ChatEvent> first = progress.emit(workspace, "u1", "conv-p");

        assertThat(first).hasSize(3);
        assertThat(first.get(0).getType()).isEqualTo(ChatEvent.TYPE_ITINERARY_SKELETON);
        JSONObject skeleton = JSON.parseObject(first.get(0).getPayload());
        assertThat(skeleton.getIntValue("dayCount")).isEqualTo(1);
        assertThat(skeleton.getJSONArray("days").getJSONObject(0).getJSONArray("poiNames"))
                .containsExactly("拙政园", "平江路");
        for (int i = 1; i <= 2; i++) {
            assertThat(first.get(i).getType()).isEqualTo(ChatEvent.TYPE_ITINERARY_DAY);
            JSONObject day = JSON.parseObject(first.get(i).getPayload());
            assertThat(day.getIntValue("day")).isEqualTo(i);
            assertThat(day.getIntValue("version")).isEqualTo(1);
            assertThat(day.getString("markdown")).startsWith("## Day " + i);
        }
        // 幂等：产物未变不重发
        assertThat(progress.emit(workspace, "u1", "conv-p")).isEmpty();
    }

    @Test
    @DisplayName("变化天重推：Day 2 内容变化仅重推该天，version 递增到 2")
    void changedDayReEmittedWithVersionBump() {
        seed(routePlan("拙政园", "平江路"), markdown("D1 v1", "D2 v1"));
        progress.emit(workspace, "u1", "conv-p");

        // 修订轮：只改 Day 2（R2 增量重组装的典型产物）
        seed(routePlan("拙政园", "平江路"), markdown("D1 v1", "D2 v2 修订后"));
        List<ChatEvent> updates = progress.emit(workspace, "u1", "conv-p");

        assertThat(updates).hasSize(1);
        JSONObject day = JSON.parseObject(updates.get(0).getPayload());
        assertThat(day.getIntValue("day")).isEqualTo(2);
        assertThat(day.getIntValue("version")).isEqualTo(2);
        assertThat(day.getString("markdown")).contains("D2 v2 修订后");
    }

    @Test
    @DisplayName("骨架重排（POI 名单变化）→ 骨架事件重发")
    void skeletonReEmittedWhenPlanChanges() {
        seed(routePlan("拙政园", "平江路"), markdown("D1 v1", "D2 v1"));
        progress.emit(workspace, "u1", "conv-p");

        seed(routePlan("拙政园", "同里古镇"), markdown("D1 v1", "D2 v1"));
        List<ChatEvent> updates = progress.emit(workspace, "u1", "conv-p");

        // 骨架重发 + Day 2 表格内容含新 POI（markdown 未变则不重发天事件）
        assertThat(updates).isNotEmpty();
        assertThat(updates.get(0).getType()).isEqualTo(ChatEvent.TYPE_ITINERARY_SKELETON);
        assertThat(JSON.parseObject(updates.get(0).getPayload())
                .getJSONArray("days").getJSONObject(0).getJSONArray("poiNames"))
                .containsExactly("拙政园", "同里古镇");
    }

    @Test
    @DisplayName("产物缺失 → 空事件不异常（流式进度绝不影响主链路）")
    void missingArtifactsEmitNothing() {
        assertThat(progress.emit(workspace, "u1", "conv-p")).isEmpty();
    }
}
