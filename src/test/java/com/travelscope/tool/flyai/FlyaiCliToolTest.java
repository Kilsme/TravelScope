package com.travelscope.tool.flyai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F1 验证：FlyaiCliTool 的响应解析与错误归一化（进程调用部分由 F6 冒烟覆盖）。
 */
class FlyaiCliToolTest {

    private static final String HOTEL_JSON = """
            {"data":{"itemList":[{"name":"杭州望湖宾馆","price":"¥618","longitude":"120.159246",
             "latitude":"30.259204","star":"豪华型","detailUrl":"https://router.feizhu.com/x"}]},
             "message":"success","status":0,"systemMessage":null}
            """;

    private static final String POI_JSON = """
            {"data":{"itemList":[{"name":"西湖风景名胜区","longitude":"120.121303",
             "latitude":"30.222741","freePoiStatus":"FREE","jumpUrl":"https://router.feizhu.com/y"}]},
             "message":"success","status":0,"systemMessage":null}
            """;

    @Test
    void parsesValidHotelResponse() {
        JSONObject parsed = FlyaiCliTool.parseResponse(HOTEL_JSON);
        assertThat(parsed.getInteger("status")).isEqualTo(0);
        JSONArray items = FlyaiCliTool.itemListOf(parsed);
        assertThat(items).hasSize(1);
        assertThat(items.getJSONObject(0).getString("name")).isEqualTo("杭州望湖宾馆");
        assertThat(FlyaiCliTool.hasItems(parsed)).isTrue();
    }

    @Test
    void parsesValidPoiResponse() {
        JSONObject parsed = FlyaiCliTool.parseResponse(POI_JSON);
        JSONArray items = FlyaiCliTool.itemListOf(parsed);
        assertThat(items.getJSONObject(0).getString("freePoiStatus")).isEqualTo("FREE");
    }

    @Test
    void emptyItemListMarksNoItems() {
        JSONObject parsed = FlyaiCliTool.parseResponse(
                "{\"data\":{\"itemList\":[]},\"status\":0}");
        assertThat(FlyaiCliTool.hasItems(parsed)).isFalse();
    }

    @Test
    void blankOutputNormalizesToError() {
        JSONObject parsed = FlyaiCliTool.parseResponse("  ");
        assertThat(parsed.containsKey("error")).isTrue();
    }

    @Test
    void nonJsonOutputNormalizesToError() {
        // ai-search 的 data 是 markdown 文本；若整体非 JSON 也应归一化
        JSONObject parsed = FlyaiCliTool.parseResponse("这不是 JSON");
        assertThat(parsed.containsKey("error")).isTrue();
    }

    @Test
    void errorStructureMatchesProjectConvention() {
        JSONObject error = FlyaiCliTool.errorResult("飞猪查询超时（30s）");
        assertThat(error.getString("error")).isEqualTo("飞猪查询超时（30s）");
        assertThat(FlyaiCliTool.hasItems(error)).isFalse();
    }

    @Test
    void itemListOfMissingDataIsEmpty() {
        JSONObject parsed = FlyaiCliTool.parseResponse("{\"status\":0}");
        assertThat(FlyaiCliTool.itemListOf(parsed)).isEmpty();
    }
}
