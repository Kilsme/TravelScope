package com.travelscope.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.travelscope.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HotelTool 测试用例
 * <p>
 * 测试策略：
 * 1. 确定性测试：API Key 未配置时返回错误 JSON
 * 2. 集成测试：需要有效的高德 API Key 和网络，验证返回真实数据
 * </p>
 */
@DisplayName("酒店搜索工具测试")
class HotelToolTest {

    private static final Logger log = LoggerFactory.getLogger(HotelToolTest.class);

    private AppProperties appProperties;
    private HotelTool hotelTool;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        // 默认不设置 API Key，用于确定性测试
        hotelTool = new HotelTool(appProperties);
    }

    // ==================== 确定性测试（无需网络） ====================

    @Test
    @DisplayName("API Key 未配置时 searchHotels 返回错误信息")
    void testSearchHotels_noApiKey() {
        String result = hotelTool.searchHotels("北京", "如家", 5);

        JSONObject json = JSON.parseObject(result);
        assertTrue(json.containsKey("error"));
        assertTrue(json.getString("error").contains("API Key"));
    }

    @Test
    @DisplayName("API Key 未配置时 searchNearbyPois 返回错误信息")
    void testSearchNearbyPois_noApiKey() {
        String result = hotelTool.searchNearbyPois("116.397428,39.90923", "餐饮服务", 1000);

        JSONObject json = JSON.parseObject(result);
        assertTrue(json.containsKey("error"));
    }

    @Test
    @DisplayName("返回结果为合法 JSON 字符串")
    void testResult_isValidJson() {
        String result = hotelTool.searchHotels("上海", "希尔顿", 3);

        assertDoesNotThrow(() -> JSON.parseObject(result));
    }

    @Test
    @DisplayName("keyword 为 null 时不应抛出异常")
    void testSearchHotels_nullKeyword() {
        String result = hotelTool.searchHotels("北京", null, 5);

        JSONObject json = JSON.parseObject(result);
        assertTrue(json.containsKey("error"));
    }

    // ==================== 集成测试（需要真实 API Key 和网络） ====================

    @Test
    @DisplayName("搜索北京如家酒店（集成测试 - 需要有效 AMAP_WEB_API_KEY）")
    @EnabledIfEnvironmentVariable(named = "AMAP_WEB_API_KEY", matches = ".+")
    void testSearchHotels_realApi() {
        appProperties.getAmap().setWebApiKey(System.getenv("AMAP_WEB_API_KEY"));

        String result = hotelTool.searchHotels("北京", "如家", 5);
        log.info("酒店搜索返回: {}", result);

        JSONObject json = JSON.parseObject(result);
        assertTrue(json.containsKey("hotels") || json.containsKey("error"),
                "返回应包含 hotels 或 error 字段");

        if (json.containsKey("hotels")) {
            int count = json.getIntValue("count");
            assertTrue(count >= 0);
            if (count > 0) {
                var hotels = json.getJSONArray("hotels");
                JSONObject first = hotels.getJSONObject(0);
                assertNotNull(first.getString("name"), "酒店名不应为 null");
                assertNotNull(first.getString("location"), "经纬度不应为 null");
                log.info("找到 {} 家酒店，第一家: {}", count, first.getString("name"));
            }
        }
    }

    @Test
    @DisplayName("搜索杭州西湖周边餐饮（集成测试 - 需要有效 AMAP_WEB_API_KEY）")
    @EnabledIfEnvironmentVariable(named = "AMAP_WEB_API_KEY", matches = ".+")
    void testSearchNearbyPois_realApi() {
        appProperties.getAmap().setWebApiKey(System.getenv("AMAP_WEB_API_KEY"));

        String result = hotelTool.searchNearbyPois("120.1550719,30.2739819", "餐饮服务", 2000);
        log.info("周边搜索返回: {}", result);

        JSONObject json = JSON.parseObject(result);
        assertTrue(json.containsKey("pois") || json.containsKey("error"),
                "返回应包含 pois 或 error 字段");

        if (json.containsKey("pois")) {
            int count = json.getIntValue("count");
            if (count > 0) {
                var pois = json.getJSONArray("pois");
                JSONObject first = pois.getJSONObject(0);
                assertNotNull(first.getString("name"), "POI 名称不应为 null");
                log.info("找到 {} 个餐饮点，第一个: {}", count, first.getString("name"));
            }
        }
    }
}
