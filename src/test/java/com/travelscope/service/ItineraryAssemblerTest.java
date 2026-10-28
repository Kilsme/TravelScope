package com.travelscope.service;

import com.travelscope.collab.ItineraryDraft;
import com.travelscope.collab.ItineraryDraft.Budget;
import com.travelscope.collab.ItineraryDraft.BudgetItem;
import com.travelscope.collab.ItineraryDraft.DraftDay;
import com.travelscope.collab.ItineraryDraft.DraftItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ItineraryAssembler（卡 C：代码组装）单测。
 * 验证 markdownBody 渲染与时间线校验逻辑（零 LLM，纯代码）。
 */
class ItineraryAssemblerTest {

    private final ItineraryAssembler assembler = new ItineraryAssembler();

    private static ItineraryDraft skeleton(List<DraftItem> day1Items, List<String> notes) {
        DraftDay day1 = new DraftDay(1, "2026-10-25", "城市风光", day1Items);
        Budget budget = new Budget("CNY", "约 1500 元/人",
                List.of(new BudgetItem("住宿", "¥600", "汉庭 2 晚")));
        return new ItineraryDraft(List.of(day1), budget, notes,
                List.of("预算为估算待确认"), "组装中");
    }

    @Test
    @DisplayName("渲染：结构字段生成完整 markdownBody（含天数/条目/预算/待确认/天气块）")
    void assembleRendersFullMarkdown() {
        ItineraryDraft skeleton = skeleton(List.of(
                new DraftItem("09:00-11:30", "栈桥游览", "青岛栈桥", "免费", null),
                new DraftItem("13:00-15:00", "八大关", "八大关风景区", "免费", "公交 31 分钟")),
                List.of("注意防晒"));

        ItineraryDraft draft = assembler.assemble(skeleton, null,
                "晴 18-22°C", "汉庭五四广场店 ¥300/晚【酒店·高德】", "D2160 ¥307/人【火车票·飞猪】");

        String md = draft.markdownBody();
        assertThat(md).contains("## Day 1").contains("09:00-11:30").contains("栈桥游览")
                .contains("## 大交通").contains("D2160").contains("【火车票·飞猪】")
                .contains("## 住宿").contains("汉庭").contains("## 天气与穿衣建议")
                .contains("## 预算汇总").contains("约 1500 元/人")
                .contains("## 待确认").contains("预算为估算待确认")
                .contains("## 注意事项").contains("注意防晒");
    }

    @Test
    @DisplayName("RouteNode 渲染：交通、酒店和餐食按类型展示")
    void routeNodesRenderByTypeSnapshot() {
        var day = new com.travelscope.collab.RoutePlan.RouteDay(1, "2026-10-25", null, List.of(
                new com.travelscope.collab.RoutePlan.RouteNode("G7331 上海虹桥→杭州东",
                        com.travelscope.collab.RoutePlan.NodeType.INTERCITY_TRANSPORT, true,
                        null, null, "08:00", "09:00", null, null, null),
                new com.travelscope.collab.RoutePlan.RouteNode("西湖",
                        com.travelscope.collab.RoutePlan.NodeType.POI, false,
                        120.1, 30.2, "10:00", "12:00", "地铁", 30, null),
                new com.travelscope.collab.RoutePlan.RouteNode("湖滨午餐",
                        com.travelscope.collab.RoutePlan.NodeType.MEAL, true,
                        null, null, "12:00", "13:00", null, null, null)), 30, null);

        DraftDay rendered = ItineraryAssembler.skeletonDay(day);

        assertThat(rendered.items()).extracting(DraftItem::activity).containsExactly(
                "🚄 城际交通：G7331 上海虹桥→杭州东", "📍 景点：西湖", "🍽 用餐：湖滨午餐");
        assertThat(rendered.items().getFirst().location()).isEqualTo("交通节点");
    }

    @Test
    @DisplayName("校验：时间线冲突（下一项开始早于上一项结束）进 notes 提示")
    void assembleDetectsTimelineConflict() {
        ItineraryDraft skeleton = skeleton(List.of(
                new DraftItem("09:00-11:30", "景点A", "地点A", null, null),
                new DraftItem("11:00-12:00", "景点B", "地点B", null, null)), // 11:00 < 11:30 冲突
                List.of());

        ItineraryDraft draft = assembler.assemble(skeleton, null, null, null, null);

        assertThat(draft.notes()).anyMatch(n -> n.contains("时间线提示") && n.contains("景点B"));
    }

    @Test
    @DisplayName("校验：时间线自洽（上一项结束 ≤ 下一项开始）无提示")
    void assembleCleanTimelineNoIssue() {
        ItineraryDraft skeleton = skeleton(List.of(
                new DraftItem("09:00-11:30", "景点A", "地点A", null, null),
                new DraftItem("13:00-15:00", "景点B", "地点B", null, null)),
                List.of());

        ItineraryDraft draft = assembler.assemble(skeleton, null, null, null, null);

        assertThat(draft.notes()).noneMatch(n -> n.contains("时间线提示"));
    }

    @Test
    @DisplayName("渲染：缓存缺失时省略对应块（天气/交通/酒店可空降级）")
    void assembleOmitsMissingCacheBlocks() {
        ItineraryDraft skeleton = skeleton(List.of(
                new DraftItem("09:00-11:30", "景点A", "地点A", null, null)), List.of());

        ItineraryDraft draft = assembler.assemble(skeleton, null, null, null, null);

        assertThat(draft.markdownBody()).doesNotContain("## 大交通")
                .doesNotContain("## 住宿").doesNotContain("## 天气与穿衣建议");
    }

    // ==================== 卡 D：防幻觉 + 偏好硬约束 ====================

    private static com.travelscope.collab.PoiShortlist shortlistOf(String... nameReasonPairs) {
        List<com.travelscope.collab.PoiShortlist.PoiItem> pois = new java.util.ArrayList<>();
        for (int i = 0; i < nameReasonPairs.length; i += 2) {
            pois.add(new com.travelscope.collab.PoiShortlist.PoiItem(
                    nameReasonPairs[i], 120.0, 31.0, 4.5, "09:00-17:00", 120, "飞猪",
                    nameReasonPairs[i + 1]));
        }
        return new com.travelscope.collab.PoiShortlist("苏州", "候选池", pois);
    }

    @Test
    @DisplayName("防幻觉：候选池没有的 POI 被剔除并记 notes，候选池有的保留")
    void hallucinatedPoiFilteredOut() {
        ItineraryDraft skeleton = skeleton(List.of(
                new DraftItem("09:00-11:30", "参观拙政园", "拙政园", null, null),
                new DraftItem("13:00-15:00", "游览幽灵古镇", "幽灵古镇", null, null)), // 候选池没有
                List.of());
        var shortlist = shortlistOf("拙政园", "园林首选", "狮子林", "假山园林", "同里古镇", "水乡古镇");

        ItineraryDraft draft = assembler.assemble(skeleton, null, null, null, null,
                shortlist, List.of());

        List<DraftItem> items = draft.days().get(0).items();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).location()).contains("拙政园");
        assertThat(draft.notes()).anyMatch(n -> n.contains("已剔除未核验项") && n.contains("幽灵古镇"));
    }

    @Test
    @DisplayName("偏好硬约束：偏好「古镇」未覆盖 → pendingConfirm 强制追加；「园林」经 reason 语义命中")
    void uncoveredPreferenceAppendedToPending() {
        ItineraryDraft skeleton = skeleton(List.of(
                new DraftItem("09:00-11:30", "参观拙政园", "拙政园", null, null)),
                List.of());
        // reason 含「园林首选」让「园林」语义命中；「古镇」无对应 → 追加
        var shortlist = shortlistOf("拙政园", "园林首选，江南园林代表", "同里古镇", "备选未排入");

        ItineraryDraft draft = assembler.assemble(skeleton, null, null, null, null,
                shortlist, List.of("园林", "古镇"));

        // 「园林」经 reason 命中不追加；「古镇」reason 只说备选未排入，days 无 → 追加
        assertThat(draft.pendingConfirm()).anyMatch(p -> p.contains("古镇") && p.contains("未在行程中体现"));
        assertThat(draft.pendingConfirm()).noneMatch(p -> p.contains("园林") && p.contains("未在行程中体现"));
    }

    @Test
    @DisplayName("偏好硬约束：偏好已覆盖（days 含同里古镇）→ 无强制追加")
    void coveredPreferenceNoAppend() {
        ItineraryDraft skeleton = skeleton(List.of(
                new DraftItem("09:00-11:30", "游览同里古镇", "同里古镇", null, null)),
                List.of());
        var shortlist = shortlistOf("拙政园", "园林首选", "同里古镇", "水乡古镇");

        ItineraryDraft draft = assembler.assemble(skeleton, null, null, null, null,
                shortlist, List.of("古镇"));

        assertThat(draft.pendingConfirm()).noneMatch(p -> p.contains("未在行程中体现"));
    }

    @Test
    @DisplayName("防幻觉：非景点项（酒店/车站/餐饮）豁免校验不被剔除")
    void nonPoiItemsExemptFromFilter() {
        ItineraryDraft skeleton = skeleton(List.of(
                new DraftItem("09:00-09:30", "高铁抵达", "苏州站", null, null),
                new DraftItem("12:00-13:00", "午餐", "松鹤楼", null, null),
                new DraftItem("13:00-15:00", "参观拙政园", "拙政园", null, null)),
                List.of());
        var shortlist = shortlistOf("拙政园", "园林首选");

        ItineraryDraft draft = assembler.assemble(skeleton, null, null, null, null,
                shortlist, List.of());

        assertThat(draft.days().get(0).items()).hasSize(3);
        assertThat(draft.notes()).noneMatch(n -> n.contains("已剔除未核验项"));
    }

    @Test
    @DisplayName("v5-F2 图片透传：shortlist imageUrl 挂载匹配行程项 + 图集行渲染（非景点行不配图）")
    void assembleAttachesPoiImagesAndRendersGallery() {
        ItineraryDraft skeleton = skeleton(List.of(
                new DraftItem("09:00-11:30", "灵隐寺", "灵隐寺", "¥45", null),
                new DraftItem("12:00-13:00", "午餐", "灵隐寺旁餐厅", "约¥30", null),
                new DraftItem("14:00-16:00", "断桥残雪", "断桥残雪", "免费", null)),
                List.of());
        com.travelscope.collab.PoiShortlist shortlist = new com.travelscope.collab.PoiShortlist(
                "杭州", null, List.of(
                new com.travelscope.collab.PoiShortlist.PoiItem(
                        "灵隐寺", 120.1, 30.24, 4.8, "06:30-18:00", 150, "HARD_COMPLETE", null,
                        "https://img.alicdn.com/ly.jpg"),
                new com.travelscope.collab.PoiShortlist.PoiItem(
                        "断桥残雪", 120.15, 30.26, 4.7, null, 90, "高德", null,
                        "https://store.is.autonavi.com/dq.jpg")));

        ItineraryDraft draft = assembler.assemble(skeleton, null, null, null, null,
                shortlist, List.of());

        // 图集行：两个 POI 图横排；午餐行不配图（防错图）
        assertThat(draft.markdownBody()).contains("📷 图集：")
                .contains("![灵隐寺](https://img.alicdn.com/ly.jpg)")
                .contains("![断桥残雪](https://store.is.autonavi.com/dq.jpg)")
                .doesNotContain("灵隐寺旁餐厅](");
        // imageUrl 随草案结构持久化（ItineraryProgress 逐天事件/修订增量链继承的载体）
        assertThat(draft.days().get(0).items().get(0).imageUrl())
                .isEqualTo("https://img.alicdn.com/ly.jpg");
        assertThat(draft.days().get(0).items().get(1).imageUrl()).isNull();
    }

    @Test
    @DisplayName("v5-F2：shortlist 无图时图集行零输出（存量行为不变）")
    void assembleWithoutImagesOmitsGallery() {
        ItineraryDraft skeleton = skeleton(List.of(
                new DraftItem("09:00-11:30", "灵隐寺", "灵隐寺", "¥45", null)), List.of());

        ItineraryDraft draft = assembler.assemble(skeleton, null, null, null, null,
                shortlistOf("灵隐寺", "无图候选"), List.of());

        assertThat(draft.markdownBody()).doesNotContain("📷 图集").doesNotContain("![");
        assertThat(draft.days().get(0).items().get(0).imageUrl()).isNull();
    }
}
