package com.travelscope.agent.tools;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F2 验证：HotelTool 双源合并（mergeHotels / normalizeName 纯函数）。
 */
class HotelToolDualSourceTest {

    private static JSONObject hotel(String name, String source) {
        JSONObject h = new JSONObject();
        h.put("name", name);
        h.put("source", source);
        return h;
    }

    @Test
    void fliggyWinsOnSameName() {
        List<JSONObject> flyai = List.of(hotel("杭州望湖宾馆", "飞猪"));
        List<JSONObject> amap = List.of(hotel("杭州望湖宾馆", "高德"), hotel("西湖大酒店", "高德"));

        List<JSONObject> merged = HotelTool.mergeHotels(flyai, amap, 10);

        assertThat(merged).hasSize(2);
        assertThat(merged.get(0).getString("source")).isEqualTo("飞猪");
        assertThat(merged.get(1).getString("name")).isEqualTo("西湖大酒店");
    }

    @Test
    void bracketSuffixNormalizedForDedup() {
        // 高德名带括号后缀、飞猪纯名——归一后应去重为一家，飞猪优先
        List<JSONObject> flyai = List.of(hotel("杭州萱蓝小筑客栈", "飞猪"));
        List<JSONObject> amap = List.of(hotel("杭州萱蓝小筑客栈(西湖店)", "高德"));

        List<JSONObject> merged = HotelTool.mergeHotels(flyai, amap, 10);

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).getString("source")).isEqualTo("飞猪");
    }

    @Test
    void respectsPageSizeLimit() {
        List<JSONObject> flyai = List.of(hotel("A", "飞猪"), hotel("B", "飞猪"), hotel("C", "飞猪"));
        List<JSONObject> amap = List.of(hotel("D", "高德"), hotel("E", "高德"));

        assertThat(HotelTool.mergeHotels(flyai, amap, 3)).hasSize(3);
    }

    @Test
    void singleSourceDegradesGracefully() {
        // 飞猪空（失败降级）→ 只剩高德；高德空 → 只剩飞猪
        assertThat(HotelTool.mergeHotels(List.of(), List.of(hotel("A", "高德")), 10))
                .hasSize(1);
        assertThat(HotelTool.mergeHotels(List.of(hotel("B", "飞猪")), List.of(), 10))
                .hasSize(1);
    }

    @Test
    void normalizeNameStripsBrackets() {
        assertThat(HotelTool.normalizeName("杭州萱蓝小筑客栈(西湖店)")).isEqualTo("杭州萱蓝小筑客栈");
        assertThat(HotelTool.normalizeName("杭州萱蓝小筑客栈（西湖店）")).isEqualTo("杭州萱蓝小筑客栈");
        assertThat(HotelTool.normalizeName("杭州望湖宾馆")).isEqualTo("杭州望湖宾馆");
        assertThat(HotelTool.normalizeName(null)).isEmpty();
    }
}
