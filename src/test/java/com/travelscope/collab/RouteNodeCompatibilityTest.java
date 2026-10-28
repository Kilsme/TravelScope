package com.travelscope.collab;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouteNodeCompatibilityTest {

    @Test
    void oldPoisJsonMigratesToPoiNodes() {
        String json = "{\"schemaVersion\":4,\"days\":[{\"day\":1,\"date\":null,"
                + "\"pois\":[{\"name\":\"西湖\",\"lng\":120.1,\"lat\":30.2,\"startTime\":\"09:00\","
                + "\"endTime\":\"11:00\",\"transitFromPrev\":null,\"commuteMinutesFromPrev\":null}],"
                + "\"commuteMinutesTotal\":0}],\"solverMeta\":{\"algorithm\":\"NN+2opt\","
                + "\"matrixSource\":\"amap\",\"durationMs\":1,\"unplaced\":[]}}";

        RoutePlan plan = JSON.parseObject(json, RoutePlan.class);

        assertThat(plan.days().getFirst().nodes()).singleElement().satisfies(node -> {
            assertThat(node.type()).isEqualTo(RoutePlan.NodeType.POI);
            assertThat(node.fixed()).isFalse();
            assertThat(node.name()).isEqualTo("西湖");
        });
    }

    @Test
    void newNodesJsonStillExposesLegacyPoisView() {
        RoutePlan.RouteNode train = new RoutePlan.RouteNode("G7331 上海虹桥→杭州东",
                RoutePlan.NodeType.INTERCITY_TRANSPORT, true, null, null,
                "08:00", "09:00", null, null, null);
        RoutePlan.RouteNode poi = new RoutePlan.RouteNode("西湖", RoutePlan.NodeType.POI, false,
                120.1, 30.2, "10:00", "12:00", "地铁", 30, null);
        RoutePlan.RouteDay day = new RoutePlan.RouteDay(1, null, null,
                List.of(train, poi), 30, null);

        RoutePlan parsed = JSON.parseObject(JSON.toJSONString(new RoutePlan(List.of(day),
                new RoutePlan.SolverMeta("NN+2opt", "amap", 1, List.of()))), RoutePlan.class);

        assertThat(parsed.days().getFirst().nodes()).hasSize(2);
        assertThat(parsed.days().getFirst().pois()).singleElement()
                .extracting(RoutePlan.RoutePoi::name).isEqualTo("西湖");
        assertThat(parsed.days().getFirst().nodes().getFirst().fixed()).isTrue();
    }
}
