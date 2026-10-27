package com.travelscope.service.poi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PoiNameNormalizerTest {

    @ParameterizedTest(name = "{0} ≈ {1}")
    @CsvSource({
            "西湖风景名胜区, 杭州西湖风景名胜区, 杭州",
            "苏州博物馆, 苏州博物馆（本馆）, 苏州",
            "臺北故宮博物院, 台北故宫博物院, 台北",
            "上海　迪士尼（Shanghai Disney）, 上海迪士尼, 上海",
            "西湖景区, 西湖風景區, 杭州"
    })
    void equivalentNamesProduceSameKey(String left, String right, String city) {
        assertThat(PoiNameNormalizer.normalize(left, city).normalizationKey())
                .isEqualTo(PoiNameNormalizer.normalize(right, city).normalizationKey());
    }

    @Test
    void preservesOriginalNameAndExposesCanonicalNameAndAliases() {
        var result = PoiNameNormalizer.normalize(" 苏州博物馆（本馆） ", "苏州");

        assertThat(result.originalName()).isEqualTo(" 苏州博物馆（本馆） ");
        assertThat(result.canonicalName()).isEqualTo("苏州博物馆");
        assertThat(result.aliases()).containsExactly("本馆");
    }

    @Test
    void parentAreaAndChildPoiMustNotMergeByCityOrRegionWords() {
        var parent = PoiNameNormalizer.normalize("杭州西湖风景名胜区", "杭州");
        var child = PoiNameNormalizer.normalize("杭州西湖雷峰塔景区", "杭州");
        var unrelated = PoiNameNormalizer.normalize("雷峰塔", "杭州");

        assertThat(parent.normalizationKey()).isNotEqualTo(child.normalizationKey());
        assertThat(parent.normalizationKey()).isNotEqualTo(unrelated.normalizationKey());
        assertThat(PoiNameNormalizer.normalize("西湖", "杭州").normalizationKey())
                .isNotEqualTo(unrelated.normalizationKey());
    }

    @Test
    void rejectsBlankNames() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PoiNameNormalizer.normalize("　 ", "杭州"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
