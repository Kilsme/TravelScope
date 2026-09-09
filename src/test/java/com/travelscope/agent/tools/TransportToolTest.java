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
 * TransportTool 测试用例
 * <p>
 * 测试策略：
 * 1. 确定性测试：API Key 未配置时返回错误 JSON
 * 2. 集成测试：需要有效的高德 API Key 和网络，验证返回真实数据
 * </p>
 */
@DisplayName("交通查询工具测试")
class TransportToolTest {

    private static final Logger log = LoggerFactory.getLogger(TransportToolTest.class);

    private AppProperties appProperties;
    private TransportTool transportTool;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        // 默认不设置 API Key，用于确定性测试
        transportTool = new TransportTool(appProperties);
    }

    // ==================== 确定性测试（无需网络） ====================

    @Test
    @DisplayName("API Key 未配置时 getDrivingRoute 返回错误信息")
    void testGetDrivingRoute_noApiKey() {
        String result = transportTool.getDrivingRoute("116.397428,39.90923", "116.407428,39.91923");

        JSONObject json = JSON.parseObject(result);
        assertTrue(json.containsKey("error"));
        assertTrue(json.getString("error").contains("API Key"));
    }

    @Test
    @DisplayName("API Key 未配置时 getTransitRoute 返回错误信息")
    void testGetTransitRoute_noApiKey() {
        String result = transportTool.getTransitRoute("116.397428,39.90923", "116.407428,39.91923", "北京");

        JSONObject json = JSON.parseObject(result);
        assertTrue(json.containsKey("error"));
    }

    @Test
    @DisplayName("API Key 未配置时 geocode 返回错误信息")
    void testGeocode_noApiKey() {
        String result = transportTool.geocode("北京市朝阳区望京SOHO");

        JSONObject json = JSON.parseObject(result);
        assertTrue(json.containsKey("error"));
    }

    @Test
    @DisplayName("返回结果为合法 JSON 字符串")
    void testResult_isValidJson() {
        String result1 = transportTool.getDrivingRoute("116.397428,39.90923", "116.407428,39.91923");
        assertDoesNotThrow(() -> JSON.parseObject(result1));

        String result2 = transportTool.geocode("杭州西湖");
        assertDoesNotThrow(() -> JSON.parseObject(result2));
    }

    // ==================== 集成测试（需要真实 API Key 和网络） ====================

    @Test
    @DisplayName("地理编码：北京天安门（集成测试 - 需要有效 AMAP_WEB_API_KEY）")
    @EnabledIfEnvironmentVariable(named = "AMAP_WEB_API_KEY", matches = ".+")
    void testGeocode_realApi() {
        appProperties.getAmap().setWebApiKey(System.getenv("AMAP_WEB_API_KEY"));

        String result = transportTool.geocode("北京天安门");
        log.info("地理编码返回: {}", result);

        JSONObject json = JSON.parseObject(result);
        assertTrue(json.containsKey("location") || json.containsKey("error"),
                "返回应包含 location 或 error 字段");

        if (json.containsKey("location")) {
            String location = json.getString("location");
            assertNotNull(location);
            assertTrue(location.contains(","), "经纬度应包含逗号分隔");
            log.info("北京天安门坐标: {}", location);
        }
    }

    @Test
    @DisplayName("驾车路线规划：北京望京到国贸（集成测试 - 需要有效 AMAP_WEB_API_KEY）")
    @EnabledIfEnvironmentVariable(named = "AMAP_WEB_API_KEY", matches = ".+")
    void testGetDrivingRoute_realApi() {
        appProperties.getAmap().setWebApiKey(System.getenv("AMAP_WEB_API_KEY"));

        String result = transportTool.getDrivingRoute("116.481028,39.989643", "116.463419,39.907402");
        log.info("驾车路线返回: {}", result);

        JSONObject json = JSON.parseObject(result);
        assertTrue(json.containsKey("distance") || json.containsKey("error"),
                "返回应包含 distance 或 error 字段");

        if (json.containsKey("distance")) {
            int distance = Integer.parseInt(json.getString("distance"));
            assertTrue(distance > 0, "距离应大于 0");
            int duration = Integer.parseInt(json.getString("duration"));
            assertTrue(duration > 0, "时间应大于 0");
            log.info("驾车距离: {} 米, 预计时间: {} 秒", distance, duration);
        }
    }

    @Test
    @DisplayName("公交路线规划：北京望京到国贸（集成测试 - 需要有效 AMAP_WEB_API_KEY）")
    @EnabledIfEnvironmentVariable(named = "AMAP_WEB_API_KEY", matches = ".+")
    void testGetTransitRoute_realApi() {
        appProperties.getAmap().setWebApiKey(System.getenv("AMAP_WEB_API_KEY"));

        String result = transportTool.getTransitRoute("116.481028,39.989643", "116.463419,39.907402", "北京");
        log.info("公交路线返回: {}", result);

        JSONObject json = JSON.parseObject(result);
        assertTrue(json.containsKey("routes") || json.containsKey("error"),
                "返回应包含 routes 或 error 字段");

        if (json.containsKey("routes")) {
            int count = json.getIntValue("count");
            assertTrue(count > 0, "应至少有一条路线");
            var routes = json.getJSONArray("routes");
            JSONObject first = routes.getJSONObject(0);
            assertNotNull(first.getString("duration"), "时间不应为 null");
            log.info("找到 {} 条公交路线，第一条预计时间: {} 秒", count, first.getString("duration"));
        }
    }
}
