package com.travelscope.service.route.quality;

import com.travelscope.collab.RoutePlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteCandidateSelectorTest {
    @Test
    void selectsBestOfAtMostThreeAndRecordsRejections() {
        RoutePlan late = plan("山景", "20:00", "22:00");
        RoutePlan early = plan("山景", "09:00", "11:00");
        var selector = new RouteCandidateSelector();

        var result = selector.select(List.of(
                        new RouteCandidateSelector.Candidate("经典覆盖", late),
                        new RouteCandidateSelector.Candidate("偏好优先", early),
                        new RouteCandidateSelector.Candidate("轻松舒适", late)),
                new RouteQualityScorer(), RouteQualityScorer.Context.defaults().withOutdoorNodes(Set.of("山景")));

        assertThat(result.selected()).isSameAs(early);
        assertThat(result.summaries()).hasSize(3).filteredOn(RoutePlan.CandidateSummary::selected).hasSize(1);
        assertThat(result.summaries()).filteredOn(s -> !s.selected())
                .allSatisfy(s -> assertThat(s.rejectionReason()).contains("低于入选方案"));
    }

    @Test
    void refusesMoreThanThreeCandidates() {
        RoutePlan p = plan("A", "09:00", "10:00");
        var candidates = List.of("a", "b", "c", "d").stream()
                .map(s -> new RouteCandidateSelector.Candidate(s, p)).toList();
        assertThatThrownBy(() -> new RouteCandidateSelector().select(candidates,
                new RouteQualityScorer(), RouteQualityScorer.Context.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RoutePlan plan(String name, String start, String end) {
        var poi = new RoutePlan.RoutePoi(name, 120.0, 30.0, start, end, null, null);
        return new RoutePlan(List.of(new RoutePlan.RouteDay(1, null, List.of(poi), 0)),
                new RoutePlan.SolverMeta("NN+2opt", "fixed-matrix", 1, List.of()));
    }
}
