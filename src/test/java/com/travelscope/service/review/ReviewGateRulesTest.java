package com.travelscope.service.review;

import com.travelscope.collab.ItineraryDraft;
import com.travelscope.collab.PoiShortlist;
import com.travelscope.collab.RoutePlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewGateRulesTest {
    @TempDir Path dir;

    @Test void duplicatePoiHasStableCode() {
        var draft = draft(List.of(item("09:00-10:00", "西湖"), item("11:00-12:00", "西湖")), "¥100");
        assertThat(ReviewGateRules.duplicatePoi(draft)).extracting(ReviewGateRules.Failure::reasonCode)
                .containsExactly("DUPLICATE_POI");
    }

    @Test void openingConflictHasStableCode() {
        var plan = plan(List.of(node("博物馆", "08:00", "10:00", RoutePlan.NodeType.POI)), List.of());
        var shortlist = new PoiShortlist("杭州", null, List.of(new PoiShortlist.PoiItem(
                "博物馆", 120.0, 30.0, 0.9, "09:00-17:00", 60, "文化", "来源")));
        assertThat(ReviewGateRules.openingHourConflict(plan, shortlist)).extracting(ReviewGateRules.Failure::reasonCode)
                .containsExactly("OPENING_HOUR_CONFLICT");
    }

    @Test void lateOutdoorAndTransportConflictHaveStableCodes() {
        var plan = plan(List.of(node("九溪烟树", "19:30", "21:00", RoutePlan.NodeType.POI),
                node("G1 杭州东→上海虹桥", "20:00", "21:00", RoutePlan.NodeType.INTERCITY_TRANSPORT)), List.of());
        assertThat(ReviewGateRules.lateOutdoorActivity(plan)).extracting(ReviewGateRules.Failure::reasonCode)
                .containsExactly("LATE_OUTDOOR_ACTIVITY");
        assertThat(ReviewGateRules.transportTimelineConflict(plan)).extracting(ReviewGateRules.Failure::reasonCode)
                .contains("TRANSPORT_TIMELINE_CONFLICT");
    }

    @Test void budgetAndUnplacedExplanationHaveStableCodes() {
        var draft = draft(List.of(item("09:00-10:00", "西湖")), "¥100");
        var plan = plan(List.of(), List.of(new RoutePlan.UnplacedPoi("灵隐寺", "DAILY_CAPACITY")));
        assertThat(ReviewGateRules.budgetArithmetic(draft)).extracting(ReviewGateRules.Failure::reasonCode)
                .containsExactly("BUDGET_ARITHMETIC_MISMATCH");
        assertThat(ReviewGateRules.unexplainedUnplaced(plan, draft)).extracting(ReviewGateRules.Failure::reasonCode)
                .containsExactly("UNEXPLAINED_UNPLACED");
    }

    @Test void newerUpstreamMakesDraftStale() throws Exception {
        Path draft = Files.writeString(dir.resolve("itinerary_draft.json"), "{}");
        Path route = Files.writeString(dir.resolve("route_plan.json"), "{}");
        Files.setLastModifiedTime(draft, FileTime.fromMillis(1000));
        Files.setLastModifiedTime(route, FileTime.fromMillis(2000));
        assertThat(ReviewGateRules.staleDraft(dir)).extracting(ReviewGateRules.Failure::reasonCode)
                .containsExactly("REVIEW_STALE_DRAFT");
    }

    private static ItineraryDraft draft(List<ItineraryDraft.DraftItem> items, String total) {
        return new ItineraryDraft(List.of(new ItineraryDraft.DraftDay(1, "2026-10-28", "主题", items)),
                new ItineraryDraft.Budget("CNY", total, List.of(
                        new ItineraryDraft.BudgetItem("住宿", "¥60", "来源"),
                        new ItineraryDraft.BudgetItem("门票", "¥50", "来源"))),
                List.of(), List.of(), "正文");
    }
    private static ItineraryDraft.DraftItem item(String time, String place) {
        return new ItineraryDraft.DraftItem(time, place, place, "¥0", null);
    }
    private static RoutePlan.RouteNode node(String name, String start, String end, RoutePlan.NodeType type) {
        return new RoutePlan.RouteNode(name, type, true, 120.0, 30.0, start, end, null, null, null);
    }
    private static RoutePlan plan(List<RoutePlan.RouteNode> nodes, List<RoutePlan.UnplacedPoi> unplaced) {
        return new RoutePlan(List.of(new RoutePlan.RouteDay(1, "2026-10-28", null, nodes, 0, null)),
                new RoutePlan.SolverMeta("NN+2opt", "fixture", 1, unplaced));
    }
}
