package com.travelscope.service.route.quality;

import com.alibaba.fastjson2.JSON;
import com.travelscope.collab.RoutePlan;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RouteQualityScorerTest {
    @Test
    void conv102ScoresDailyBalanceAndLateOutdoorLow() throws Exception {
        RoutePlan plan = JSON.parseObject(Files.readString(Path.of(
                ".agentscope/workspace/2/tasks/conv-102/route_plan.json")), RoutePlan.class);
        RouteQualityScorer scorer = new RouteQualityScorer();

        var result = scorer.score(plan, RouteQualityScorer.Context.defaults()
                .withOutdoorNodes(Set.of("九溪烟树", "西湖风景名胜区")));

        assertThat(result.scores().get(RouteQualityScorer.Dimension.DAILY_BALANCE)).isLessThan(80);
        assertThat(result.scores().get(RouteQualityScorer.Dimension.LATE_ACTIVITY)).isLessThan(80);
        assertThat(result.penalties()).anySatisfy(p -> {
            assertThat(p.day()).isEqualTo(1);
            assertThat(p.node()).isEqualTo("九溪烟树");
            assertThat(p.reasonCode()).isEqualTo("LATE_OUTDOOR_ACTIVITY");
        });
    }

    @Test
    void weightsAreConfigurableAndEveryPenaltyIsLocatable() {
        RoutePlan plan = JSON.parseObject("{\"schemaVersion\":4,\"days\":[{\"day\":1,\"commuteMinutesTotal\":180,"
                + "\"pois\":[{\"name\":\"山景\",\"lng\":120.1,\"lat\":30.2,\"startTime\":\"20:00\",\"endTime\":\"22:00\"}]}],"
                + "\"solverMeta\":{\"algorithm\":\"NN+2opt\",\"matrixSource\":\"fixture\",\"durationMs\":1,\"unplaced\":[]}}", RoutePlan.class);
        var weights = RouteQualityScorer.Weights.defaults().with(Map.of(
                RouteQualityScorer.Dimension.COMMUTE, 9,
                RouteQualityScorer.Dimension.LATE_ACTIVITY, 1));

        var result = new RouteQualityScorer().score(plan,
                RouteQualityScorer.Context.defaults().withOutdoorNodes(Set.of("山景")).withWeights(weights));

        assertThat(result.penalties()).allSatisfy(p -> {
            assertThat(p.day()).isPositive();
            assertThat(p.node()).isNotBlank();
            assertThat(p.reasonCode()).isNotBlank();
        });
        assertThat(result.totalScore()).isBetween(0.0, 100.0);
    }
}
