package com.travelscope.service.review;

import com.travelscope.collab.ItineraryDraft;
import com.travelscope.collab.RoutePlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QualityRubricTest {
    @Test void scoresEightDimensionsAndProducesActionableSuggestions() {
        var draft = new ItineraryDraft(List.of(), null, List.of(), List.of(), "");
        var result = new QualityRubric().score(draft, null, null, null);
        assertThat(result.scores()).hasSize(8);
        assertThat(result.total()).isLessThan(result.threshold());
        assertThat(result.suggestions()).isNotEmpty()
                .allSatisfy(s -> assertThat(s.action()).isNotBlank());
    }

    @Test void completeRouteAndDraftAreDeliverable() {
        var node = new RoutePlan.RouteNode("西湖", RoutePlan.NodeType.POI, true, 120.0, 30.0,
                "09:00", "11:00", null, null, "景点");
        var plan = new RoutePlan(List.of(new RoutePlan.RouteDay(1, null, null, List.of(node), 30, null)),
                new RoutePlan.SolverMeta("NN+2opt", "fixture", 1, List.of()));
        var item = new ItineraryDraft.DraftItem("09:00-11:00", "西湖", "西湖", "¥0", null);
        var budget = new ItineraryDraft.Budget("CNY", "¥100", List.of(
                new ItineraryDraft.BudgetItem("交通", "¥100", "确定来源")));
        var draft = new ItineraryDraft(List.of(new ItineraryDraft.DraftDay(1, null, "湖景", List.of(item))),
                budget, List.of(), List.of(), "完整正文");
        var result = new QualityRubric().score(draft, plan, null, null);
        assertThat(result.scores().get(QualityRubric.Dimension.ACTIONABILITY)).isEqualTo(100);
        assertThat(result.total()).isGreaterThanOrEqualTo(70);
    }
}
