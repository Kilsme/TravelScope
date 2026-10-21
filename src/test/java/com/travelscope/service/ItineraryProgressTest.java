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
 * 渲染分段化（v6 阶段五）：逐天事件主路径=ItineraryAssembler 逐天出口结构化渲染
 * （修订经 days 结构体现）；days 空的极端产物回退「## Day N」正则切分。
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

    /** 双日草案（结构化渲染主路径的数据源）：day2Note/day2Theme 可变以模拟修订轮 */
    private static ItineraryDraft draft(String day2Theme, String day2Note) {
        return new ItineraryDraft(List.of(
                new ItineraryDraft.DraftDay(1, "2026-10-24", "园林", List.of(
                        new ItineraryDraft.DraftItem("09:00-11:00", "拙政园", "拙政园", "¥70", "D1 首推"))),
                new ItineraryDraft.DraftDay(2, "2026-10-25", day2Theme, List.of(
                        new ItineraryDraft.DraftItem("13:00-15:00", "平江路", "平江路", "免费", day2Note)))),
                null, List.of(), List.of(), "# 行程方案（2 天）\n\n## Day 1…\n\n## Day 2…\n");
    }

    private void seed(RoutePlan plan, ItineraryDraft d) {
        workspace.writeRoutePlan("u1", "conv-p", plan);
        workspace.writeItineraryDraft("u1", "conv-p", d);
    }

    @Test
    @DisplayName("splitDaySections 回退路径：按「## Day N」切分，天节含标题，非天节（大交通/预算）不入")
    void splitDaySections() {
        String markdownBody = "# 行程方案（2 天）\n\n"
                + "## Day 1（2026-10-24）园林\n\nD1 内容\n\n"
                + "## Day 2（2026-10-25）古迹\n\nD2 内容\n\n"
                + "## 大交通\n\nG7331 上海虹桥→苏州\n\n## 预算汇总\n\n- 总预算：1200（CNY）\n";
        Map<Integer, String> sections = ItineraryProgress.splitDaySections(markdownBody);

        assertThat(sections).containsOnlyKeys(1, 2);
        assertThat(sections.get(1)).startsWith("## Day 1").contains("D1 内容").doesNotContain("D2 内容");
        assertThat(sections.get(2)).startsWith("## Day 2").contains("D2 内容");
        assertThat(sections.values()).noneSatisfy(s -> assertThat(s).contains("大交通"));
    }

    @Test
    @DisplayName("首推（结构化渲染）：骨架+全部天 v1，seq 单调 1/2/3；内容未变重推为空（幂等）")
    void firstEmitThenIdempotent() {
        seed(routePlan("拙政园", "平江路"), draft("古迹", "D2 v1"));

        List<ChatEvent> first = progress.emit(workspace, "u1", "conv-p");

        assertThat(first).hasSize(3);
        assertThat(first.get(0).getType()).isEqualTo(ChatEvent.TYPE_ITINERARY_SKELETON);
        JSONObject skeleton = JSON.parseObject(first.get(0).getPayload());
        assertThat(skeleton.getIntValue("dayCount")).isEqualTo(1);
        assertThat(skeleton.getIntValue("seq")).isEqualTo(1);
        assertThat(skeleton.getJSONArray("days").getJSONObject(0).getJSONArray("poiNames"))
                .containsExactly("拙政园", "平江路");
        for (int i = 1; i <= 2; i++) {
            assertThat(first.get(i).getType()).isEqualTo(ChatEvent.TYPE_ITINERARY_DAY);
            JSONObject day = JSON.parseObject(first.get(i).getPayload());
            assertThat(day.getIntValue("day")).isEqualTo(i);
            assertThat(day.getIntValue("version")).isEqualTo(1);
            assertThat(day.getIntValue("seq")).isEqualTo(i + 1);
            // 逐天出口结构化渲染：标题带 date/theme，表格含 POI 名
            assertThat(day.getString("markdown")).startsWith("## Day " + i + "（2026-10-2" + (3 + i) + "）")
                    .contains("| 时间 | 活动 | 地点 | 费用 |");
        }
        assertThat(JSON.parseObject(first.get(1).getPayload()).getString("markdown")).contains("拙政园");
        assertThat(JSON.parseObject(first.get(2).getPayload()).getString("markdown")).contains("D2 v1");
        // 幂等：产物未变不重发
        assertThat(progress.emit(workspace, "u1", "conv-p")).isEmpty();
    }

    @Test
    @DisplayName("变化天重推（结构化渲染）：Day 2 结构变化仅重推该天，version 2 / seq 递增")
    void changedDayReEmittedWithVersionBump() {
        seed(routePlan("拙政园", "平江路"), draft("古迹", "D2 v1"));
        progress.emit(workspace, "u1", "conv-p");

        // 修订轮：Day 2 的 theme 与 note 变化（P3 增量重组装的典型产物——结构级差量）
        seed(routePlan("拙政园", "平江路"), draft("水巷夜游", "D2 v2 修订后"));
        List<ChatEvent> updates = progress.emit(workspace, "u1", "conv-p");

        assertThat(updates).hasSize(1);
        JSONObject day = JSON.parseObject(updates.get(0).getPayload());
        assertThat(day.getIntValue("day")).isEqualTo(2);
        assertThat(day.getIntValue("version")).isEqualTo(2);
        assertThat(day.getIntValue("seq")).isEqualTo(4);
        assertThat(day.getString("markdown")).contains("水巷夜游").contains("D2 v2 修订后");
    }

    @Test
    @DisplayName("骨架重排（POI 名单变化）→ 骨架事件重发（seq 递增）")
    void skeletonReEmittedWhenPlanChanges() {
        seed(routePlan("拙政园", "平江路"), draft("古迹", "D2 v1"));
        progress.emit(workspace, "u1", "conv-p");

        seed(routePlan("拙政园", "同里古镇"), draft("古迹", "D2 v1"));
        List<ChatEvent> updates = progress.emit(workspace, "u1", "conv-p");

        assertThat(updates).isNotEmpty();
        assertThat(updates.get(0).getType()).isEqualTo(ChatEvent.TYPE_ITINERARY_SKELETON);
        JSONObject skeleton = JSON.parseObject(updates.get(0).getPayload());
        assertThat(skeleton.getIntValue("seq")).isEqualTo(4);
        assertThat(skeleton.getJSONArray("days").getJSONObject(0).getJSONArray("poiNames"))
                .containsExactly("拙政园", "同里古镇");
    }

    @Test
    @DisplayName("回退路径：days 空而 markdownBody 有 Day 节 → 正则切分照常出逐天事件")
    void emptyDaysFallsBackToMarkdownSplit() {
        workspace.writeRoutePlan("u1", "conv-p", routePlan("拙政园", "平江路"));
        workspace.writeItineraryDraft("u1", "conv-p", new ItineraryDraft(List.of(),
                null, List.of(), List.of(),
                "## Day 1（2026-10-24）园林\n\nD1 内容\n\n## Day 2（2026-10-25）古迹\n\nD2 内容\n"));

        List<ChatEvent> events = progress.emit(workspace, "u1", "conv-p");

        assertThat(events).hasSize(3);
        JSONObject day2 = JSON.parseObject(events.get(2).getPayload());
        assertThat(day2.getIntValue("day")).isEqualTo(2);
        assertThat(day2.getString("markdown")).contains("D2 内容");
    }

    @Test
    @DisplayName("产物缺失 → 空事件不异常（流式进度绝不影响主链路）")
    void missingArtifactsEmitNothing() {
        assertThat(progress.emit(workspace, "u1", "conv-p")).isEmpty();
    }
}
