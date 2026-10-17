package com.travelscope.agent.tools;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F3 验证：AttractionTool 双源合并（mergePois 纯函数）。
 */
class AttractionToolDualSourceTest {

    private static JSONObject poi(String name, String source) {
        JSONObject p = new JSONObject();
        p.put("name", name);
        p.put("source", source);
        return p;
    }

    @Test
    void fliggyWinsOnSameName() {
        List<JSONObject> flyai = List.of(poi("西湖风景名胜区", "飞猪"));
        List<JSONObject> amap = List.of(poi("西湖风景名胜区", "高德"), poi("灵隐寺", "高德"));

        List<JSONObject> merged = AttractionTool.mergePois(flyai, amap, 10);

        assertThat(merged).hasSize(2);
        assertThat(merged.get(0).getString("source")).isEqualTo("飞猪");
        assertThat(merged.get(1).getString("name")).isEqualTo("灵隐寺");
    }

    @Test
    void bracketSuffixNormalizedForDedup() {
        List<JSONObject> flyai = List.of(poi("雷峰塔", "飞猪"));
        List<JSONObject> amap = List.of(poi("雷峰塔(西湖十景)", "高德"));

        assertThat(AttractionTool.mergePois(flyai, amap, 10)).hasSize(1);
    }

    @Test
    void respectsPageSizeAndSingleSourceDegrade() {
        List<JSONObject> flyai = List.of(poi("A", "飞猪"), poi("B", "飞猪"), poi("C", "飞猪"));
        assertThat(AttractionTool.mergePois(flyai, List.of(), 2)).hasSize(2);
        assertThat(AttractionTool.mergePois(List.of(), List.of(poi("D", "高德")), 10)).hasSize(1);
    }
}
