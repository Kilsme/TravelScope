package com.travelscope.service.poi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PoiEntityResolverTest {

    private final PoiEntityResolver resolver = new PoiEntityResolver();

    @Test
    void sameSourceIdAlwaysMerges() {
        var result = resolver.resolve(List.of(
                poi("a", "amap:B023", "西湖", 120.1213, 30.2227),
                poi("b", "amap:B023", "完全不同展示名", 121.0, 31.0)), "杭州");

        assertThat(result.entities()).singleElement().satisfies(entity -> {
            assertThat(entity.mergedFrom()).containsExactlyInAnyOrder("a", "b");
            assertThat(entity.mergeReason()).isEqualTo(PoiEntityResolver.MergeReason.SAME_SOURCE_ID);
        });
    }

    @Test
    void conv102WestLakeCoordinatesMergeButLeifengPagodaStaysSeparate() {
        var result = resolver.resolve(List.of(
                poi("fliggy-westlake", "fliggy:westlake", "西湖风景名胜区", 120.121303, 30.222741),
                poi("amap-westlake", "amap:westlake", "杭州西湖风景名胜区", 120.121358, 30.222692),
                poi("amap-leifeng", "amap:leifeng", "雷峰塔景区", 120.148849, 30.230934)), "杭州");

        assertThat(result.entities()).hasSize(2);
        assertThat(result.entities()).anySatisfy(entity -> {
            assertThat(entity.mergedFrom()).containsExactlyInAnyOrder("fliggy-westlake", "amap-westlake");
            assertThat(entity.mergeReason()).isEqualTo(PoiEntityResolver.MergeReason.NAME_COORDINATE_MATCH);
        });
        assertThat(result.entities()).anySatisfy(entity ->
                assertThat(entity.mergedFrom()).containsExactly("amap-leifeng"));
    }

    @Test
    void parentChildRelationshipDoesNotMerge() {
        var result = resolver.resolve(List.of(
                poi("westlake", null, "西湖风景名胜区", 120.1488, 30.2309),
                poi("leifeng", null, "西湖雷峰塔景区", 120.148849, 30.230934)), "杭州");

        assertThat(result.entities()).hasSize(2);
        assertThat(result.relations()).singleElement().satisfies(relation -> {
            assertThat(relation.type()).isEqualTo(PoiEntityResolver.RelationType.PARENT_CHILD);
            assertThat(List.of(relation.leftId(), relation.rightId()))
                    .containsExactlyInAnyOrder("westlake", "leifeng");
        });
    }

    @Test
    void closeCoordinatesWithUnrelatedNamesDoNotMerge() {
        var result = resolver.resolve(List.of(
                poi("westlake", null, "西湖", 120.1488, 30.2309),
                poi("restaurant", null, "楼外楼餐厅", 120.14881, 30.23091)), "杭州");
        assertThat(result.entities()).hasSize(2);
    }

    @Test
    void resultIsIndependentOfInputOrder() {
        var a = poi("z", "fliggy:westlake", "西湖风景名胜区", 120.121303, 30.222741);
        var b = poi("a", "amap:westlake", "杭州西湖风景名胜区", 120.121358, 30.222692);
        var c = poi("m", "amap:leifeng", "雷峰塔景区", 120.148849, 30.230934);

        var forward = resolver.resolve(List.of(a, b, c), "杭州");
        var reverse = resolver.resolve(List.of(c, b, a), "杭州");

        assertThat(reverse).isEqualTo(forward);
    }

    private static PoiEntityResolver.PoiInput poi(String id, String sourceId, String name,
                                                   double lng, double lat) {
        return new PoiEntityResolver.PoiInput(id, sourceId, name, lng, lat);
    }
}
