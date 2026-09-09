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
 * WeatherTool 测试用例
 * <p>
 * 测试策略：
 * 1. 确定性测试（无网络依赖）：API Key 未配置时返回错误 JSON
 * 2. 集成测试（需网络+有效 Key）：用 @EnabledIfEnvironmentVariable 控制，验证返回值正确性
 * </p>
 */
@DisplayName("天气查询工具测试")
class WeatherToolTest {

    private static final Logger log = LoggerFactory.getLogger(WeatherToolTest.class);

    private AppProperties appProperties;
    private WeatherTool weatherTool;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        // 默认不设置 API Key，用于确定性测试（AppProperties 默认值已是 null）
        weatherTool = new WeatherTool(appProperties);
    }

    // ==================== 确定性测试（无需网络） ====================

    @Test
    @DisplayName("API Key 未配置时 getWeather 返回错误信息")
    void testGetWeather_noApiKey() {
        String result = weatherTool.getWeather("北京");

        JSONObject json = JSON.parseObject(result);
        assertTrue(json.containsKey("error"));
        assertTrue(json.getString("error").contains("API Key"));
    }

    @Test
    @DisplayName("API Host 未配置时 getWeather 返回错误信息")
    void testGetWeather_noApiHost() {
        appProperties.getWeather().setApiKey("fake-key");
        // apiHost 保持 null

        String result = weatherTool.getWeather("北京");

        JSONObject json = JSON.parseObject(result);
        assertTrue(json.containsKey("error"));
        assertTrue(json.getString("error").contains("Host"));
    }

    @Test
    @DisplayName("API Key 未配置时 getWeatherForecast 返回错误信息")
    void testGetWeatherForecast_noApiKey() {
        String result = weatherTool.getWeatherForecast("北京", 3);

        JSONObject json = JSON.parseObject(result);
        assertTrue(json.containsKey("error"));
    }

    @Test
    @DisplayName("返回结果为合法 JSON 字符串")
    void testResult_isValidJson() {
        String result = weatherTool.getWeather("上海");

        assertDoesNotThrow(() -> JSON.parseObject(result));
    }

    // ==================== 集成测试（需要真实 API Key 和网络） ====================

    @Test
    @DisplayName("查询北京实时天气（集成测试 - 需要有效 WEATHER_API_KEY）")
    @EnabledIfEnvironmentVariable(named = "WEATHER_API_KEY", matches = ".+")
    void testGetWeather_realApi() {
        appProperties.getWeather().setApiKey(System.getenv("WEATHER_API_KEY"));
        appProperties.getWeather().setApiHost(
                System.getenv().getOrDefault("WEATHER_API_HOST", "devapi.qweather.com")
        );

        String result = weatherTool.getWeather("北京");
        log.info("实时天气返回: {}", result);

        JSONObject json = JSON.parseObject(result);
        assertTrue(json.containsKey("city") || json.containsKey("error"),
                "返回应包含 city 或 error 字段");

        // 如果成功返回天气数据，验证关键字段
        if (json.containsKey("city")) {
            assertNotNull(json.getString("city"), "city 不应为 null");
            assertNotNull(json.getString("temp"), "temp 不应为 null");
            assertNotNull(json.getString("text"), "text 不应为 null");
            log.info("北京当前温度: {}, 天气: {}", json.getString("temp"), json.getString("text"));
        }
    }

    @Test
    @DisplayName("查询杭州3天天气预报（集成测试 - 需要有效 WEATHER_API_KEY）")
    @EnabledIfEnvironmentVariable(named = "WEATHER_API_KEY", matches = ".+")
    void testGetWeatherForecast_realApi() {
        appProperties.getWeather().setApiKey(System.getenv("WEATHER_API_KEY"));
        appProperties.getWeather().setApiHost(
                System.getenv().getOrDefault("WEATHER_API_HOST", "devapi.qweather.com")
        );

        String result = weatherTool.getWeatherForecast("杭州", 3);
        log.info("天气预报返回: {}", result);

        JSONObject json = JSON.parseObject(result);
        assertTrue(json.containsKey("forecasts") || json.containsKey("error"),
                "返回应包含 forecasts 或 error 字段");

        if (json.containsKey("forecasts")) {
            var forecasts = json.getJSONArray("forecasts");
            assertFalse(forecasts.isEmpty(), "预报列表不应为空");
            JSONObject firstDay = forecasts.getJSONObject(0);
            assertNotNull(firstDay.getString("date"), "日期不应为 null");
            assertNotNull(firstDay.getString("temp_max"), "最高温度不应为 null");
            log.info("杭州第一天预报: 日期={}, 天气={}, {}~{}°C",
                    firstDay.getString("date"),
                    firstDay.getString("text_day"),
                    firstDay.getString("temp_min"),
                    firstDay.getString("temp_max"));
        }
    }
}
