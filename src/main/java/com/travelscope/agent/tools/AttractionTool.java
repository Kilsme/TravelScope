package com.travelscope.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.travelscope.config.AppProperties;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 景点搜索工具（高德地图 POI 搜索）
 * <p>
 * 通过高德地图 Web API 查询景点信息：
 * - 城市景点搜索：/place/text，POI 类型码 110000（风景名胜）—— searchPois，
 *   v3 起 Planner 的 POI 初查直调入口（不走子 Agent）
 * - 周边景点搜索：/place/around，按坐标 + 半径搜索 —— searchNearbyAttractions
 * 接口文档: https://lbs.amap.com/api/webservice/guide/api/search
 * </p>
 */
@Component
public class AttractionTool {

    private static final Logger log = LoggerFactory.getLogger(AttractionTool.class);

    /** POI 类型码：风景名胜 */
    private static final String POI_TYPE_SCENIC = "110000";

    private final AppProperties appProperties;
    private final OkHttpClient httpClient;

    public AttractionTool(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 搜索指定城市的景点（POI 初查统一入口，v3：Planner 直调，不走子 Agent）
     *
     * @param city     城市名称（如 "北京"、"杭州"）
     * @param keyword  搜索关键词（景点名、主题等，可为空）
     * @param pageSize 返回结果数量
     * @return 景点列表 JSON 字符串
     */
    @Tool(description = "搜索指定城市的景点 POI（searchPois，城市景点初查统一入口），"
            + "包括景点名称、地址、经纬度、评分等。用于旅游行程的景点初查与选择。")
    public String searchPois(
            @ToolParam(name = "city", description = "城市名称，如 '北京'、'杭州'、'成都'")
            String city,
            @ToolParam(name = "keyword", description = "搜索关键词，如 '西湖'、'博物馆'、'古镇'，传空字符串则搜索城市热门景点")
            String keyword,
            @ToolParam(name = "pageSize", description = "返回结果数量，建议5-20")
            int pageSize
    ) {
        String apiKey = appProperties.getAmap().getWebApiKey();
        String baseUrl = appProperties.getAmap().getBaseUrl();

        if (apiKey == null || apiKey.isBlank()) {
            return errorResult("高德地图 API Key 未配置，请检查环境变量 AMAP_WEB_API_KEY");
        }

        log.info("搜索景点POI: city={}, keyword={}, pageSize={}", city, keyword, pageSize);

        HttpUrl url = HttpUrl.parse(baseUrl + "/place/text").newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("keywords", keyword == null || keyword.isBlank() ? "景点" : keyword)
                .addQueryParameter("city", city)
                .addQueryParameter("citylimit", "true")
                .addQueryParameter("types", POI_TYPE_SCENIC)
                .addQueryParameter("offset", String.valueOf(Math.min(Math.max(pageSize, 1), 25)))
                .addQueryParameter("page", "1")
                .addQueryParameter("extensions", "all")
                .addQueryParameter("output", "JSON")
                .build();

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return errorResult("高德 API 请求失败，HTTP " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "{}";
            return parseAttractionResults(body);
        } catch (IOException e) {
            log.error("搜索景点异常: city={}", city, e);
            return errorResult("搜索景点失败: " + e.getMessage());
        }
    }

    /**
     * 搜索指定位置周边的景点
     *
     * @param location 中心点坐标（经纬度，如 "116.397428,39.90923"）
     * @param radius   搜索半径（米）
     * @param pageSize 返回结果数量
     * @return 景点列表 JSON 字符串
     */
    @Tool(description = "搜索指定位置周边的景点，包括景点名称、地址、距离等。用于以酒店或某景点为中心规划游览路线。")
    public String searchNearbyAttractions(
            @ToolParam(name = "location", description = "中心点经纬度坐标，格式: '经度,纬度'，如 '116.397428,39.90923'")
            String location,
            @ToolParam(name = "radius", description = "搜索半径（米），建议1000-5000")
            int radius,
            @ToolParam(name = "pageSize", description = "返回结果数量，建议5-20")
            int pageSize
    ) {
        String apiKey = appProperties.getAmap().getWebApiKey();
        String baseUrl = appProperties.getAmap().getBaseUrl();

        if (apiKey == null || apiKey.isBlank()) {
            return errorResult("高德地图 API Key 未配置");
        }

        log.info("周边景点搜索: location={}, radius={}, pageSize={}", location, radius, pageSize);

        HttpUrl url = HttpUrl.parse(baseUrl + "/place/around").newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("location", location)
                .addQueryParameter("types", POI_TYPE_SCENIC)
                .addQueryParameter("radius", String.valueOf(radius))
                .addQueryParameter("offset", String.valueOf(Math.min(Math.max(pageSize, 1), 25)))
                .addQueryParameter("page", "1")
                .addQueryParameter("extensions", "all")
                .addQueryParameter("output", "JSON")
                .build();

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return errorResult("高德 API 请求失败，HTTP " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "{}";
            return parseAttractionResults(body);
        } catch (IOException e) {
            log.error("周边景点搜索异常: location={}", location, e);
            return errorResult("周边景点搜索失败: " + e.getMessage());
        }
    }

    /** 解析景点搜索结果 */
    private String parseAttractionResults(String body) {
        JSONObject json = JSON.parseObject(body);
        int status = json.getIntValue("status");
        if (status != 1) {
            return errorResult("高德 API 返回错误: " + json.getString("info"));
        }
        JSONArray pois = json.getJSONArray("pois");
        if (pois == null || pois.isEmpty()) {
            JSONObject empty = new JSONObject();
            empty.put("count", 0);
            empty.put("attractions", new ArrayList<>());
            return JSON.toJSONString(empty);
        }

        List<JSONObject> attractions = new ArrayList<>();
        for (int i = 0; i < pois.size(); i++) {
            JSONObject poi = pois.getJSONObject(i);
            JSONObject item = new JSONObject();
            item.put("name", poi.getString("name"));
            item.put("address", poi.getString("address"));
            item.put("location", poi.getString("location"));
            item.put("tel", poi.getString("tel"));
            item.put("type", poi.getString("type"));
            if (poi.containsKey("distance")) {
                item.put("distance", poi.getString("distance"));
            }
            // extensions=all 时部分 POI 带 biz_ext（评分、人均消费）
            JSONObject bizExt = poi.getJSONObject("biz_ext");
            if (bizExt != null) {
                item.put("rating", bizExt.getString("rating"));
                item.put("cost", bizExt.getString("cost"));
            }
            attractions.add(item);
        }

        JSONObject result = new JSONObject();
        result.put("count", attractions.size());
        result.put("attractions", attractions);
        return JSON.toJSONString(result);
    }

    private String errorResult(String msg) {
        JSONObject error = new JSONObject();
        error.put("error", msg);
        return JSON.toJSONString(error);
    }
}
