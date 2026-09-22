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
 * 酒店搜索工具（高德地图 POI 搜索）
 * <p>
 * 通过高德地图 Web API 的关键字搜索 POI 接口查询酒店信息。
 * 接口文档: https://lbs.amap.com/api/webservice/guide/api/search
 * </p>
 */
@Component
public class HotelTool {

    private static final Logger log = LoggerFactory.getLogger(HotelTool.class);

    /** POI 类型码：住宿服务 */
    private static final String POI_TYPE_ACCOMMODATION = "100000";

    /** 中文 POI 类型名称 → 高德类型码映射 */
    private static String resolveTypeCode(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        return switch (type) {
            case "住宿服务", "酒店", "宾馆" -> "100000";
            case "餐饮服务", "餐厅", "美食" -> "050000";
            case "风景名胜", "景点", "公园" -> "110000";
            case "购物服务", "商场", "超市" -> "060000";
            case "交通设施", "机场", "火车站" -> "150000";
            case "医疗保健", "医院", "诊所" -> "090000";
            case "教育文化", "学校", "博物馆" -> "140000";
            default -> "";  // 未知类型不传 types，用 keywords 模糊搜索
        };
    }

    private final AppProperties appProperties;
    private final OkHttpClient httpClient;

    public HotelTool(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 搜索指定城市的酒店
     * <p>
     * 调用高德地图 POI 关键字搜索接口，按城市过滤，返回酒店列表。
     * </p>
     *
     * @param city     城市名称（如 "北京"、"杭州"）
     * @param keyword  搜索关键词（酒店名、品牌等，可为空）
     * @param pageSize 返回结果数量
     * @return 酒店列表 JSON 字符串
     */
    @Tool(description = "搜索指定城市的酒店信息，包括酒店名称、地址、经纬度、联系电话等。用于旅游住宿规划。")
    public String searchHotels(
            @ToolParam(name = "city", description = "城市名称，如 '北京'、'杭州'")
            String city,
            @ToolParam(name = "keyword", description = "搜索关键词，如酒店名或品牌名称，传空字符串则搜索全部酒店")
            String keyword,
            @ToolParam(name = "pageSize", description = "返回结果数量，建议5-20")
            int pageSize
    ) {
        String apiKey = appProperties.getAmap().getWebApiKey();
        String baseUrl = appProperties.getAmap().getBaseUrl();

        if (apiKey == null || apiKey.isBlank()) {
            return errorResult("高德地图 API Key 未配置，请检查环境变量 AMAP_WEB_API_KEY");
        }

        log.info("搜索酒店: city={}, keyword={}, pageSize={}", city, keyword, pageSize);

        // 构建请求 URL
        HttpUrl url = HttpUrl.parse(baseUrl + "/place/text").newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("keywords", keyword == null ? "酒店" : keyword)
                .addQueryParameter("city", city)
                .addQueryParameter("citylimit", "true")
                .addQueryParameter("types", POI_TYPE_ACCOMMODATION)
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
            return parseHotelResults(body);
        } catch (IOException e) {
            log.error("搜索酒店异常: city={}", city, e);
            return errorResult("搜索酒店失败: " + e.getMessage());
        }
    }

    /**
     * 搜索指定位置周边的 POI（餐厅、景点等）
     *
     * @param location 中心点坐标（经纬度，如 "116.397428,39.90923"）
     * @param type     POI 类型（餐饮服务/风景名胜等）
     * @param radius   搜索半径（米）
     * @return POI 列表
     */
    @Tool(description = "搜索指定位置周边的 POI（兴趣点），包括餐厅、景点、商场等。用于行程规划和餐饮推荐。")
    public String searchNearbyPois(
            @ToolParam(name = "location", description = "中心点经纬度坐标，格式: '经度,纬度'，如 '116.397428,39.90923'")
            String location,
            @ToolParam(name = "type", description = "POI类型，如 '餐饮服务'、'风景名胜'、'住宿服务'")
            String type,
            @ToolParam(name = "radius", description = "搜索半径（米），默认1000")
            int radius
    ) {
        String apiKey = appProperties.getAmap().getWebApiKey();
        String baseUrl = appProperties.getAmap().getBaseUrl();

        if (apiKey == null || apiKey.isBlank()) {
            return errorResult("高德地图 API Key 未配置");
        }

        log.info("周边搜索: location={}, type={}, radius={}", location, type, radius);

        String typeCode = resolveTypeCode(type);
        HttpUrl.Builder urlBuilder = HttpUrl.parse(baseUrl + "/place/around").newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("location", location)
                .addQueryParameter("radius", String.valueOf(radius))
                .addQueryParameter("offset", "10")
                .addQueryParameter("page", "1")
                .addQueryParameter("extensions", "all")
                .addQueryParameter("output", "JSON");

        // 有类型码时用 types 精确过滤，否则用 keywords 模糊搜索
        if (!typeCode.isEmpty()) {
            urlBuilder.addQueryParameter("types", typeCode);
        } else if (type != null && !type.isBlank()) {
            urlBuilder.addQueryParameter("keywords", type);
        }

        HttpUrl url = urlBuilder.build();

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return errorResult("高德 API 请求失败，HTTP " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "{}";
            return parsePoiResults(body);
        } catch (IOException e) {
            log.error("周边搜索异常: location={}", location, e);
            return errorResult("周边搜索失败: " + e.getMessage());
        }
    }

    /** 解析酒店搜索结果 */
    private String parseHotelResults(String body) {
        JSONObject json = JSON.parseObject(body);
        int status = json.getIntValue("status");
        if (status != 1) {
            return errorResult("高德 API 返回错误: " + json.getString("info"));
        }
        JSONArray pois = json.getJSONArray("pois");
        if (pois == null || pois.isEmpty()) {
            JSONObject empty = new JSONObject();
            empty.put("count", 0);
            empty.put("hotels", new ArrayList<>());
            return JSON.toJSONString(empty);
        }

        List<JSONObject> hotels = new ArrayList<>();
        for (int i = 0; i < pois.size(); i++) {
            JSONObject poi = pois.getJSONObject(i);
            JSONObject hotel = new JSONObject();
            hotel.put("name", poi.getString("name"));
            hotel.put("address", poi.getString("address"));
            hotel.put("location", poi.getString("location"));
            hotel.put("tel", poi.getString("tel"));
            hotel.put("type", poi.getString("type"));
            hotels.add(hotel);
        }

        JSONObject result = new JSONObject();
        result.put("count", hotels.size());
        result.put("hotels", hotels);
        return JSON.toJSONString(result);
    }

    /** 解析周边搜索结果 */
    private String parsePoiResults(String body) {
        JSONObject json = JSON.parseObject(body);
        int status = json.getIntValue("status");
        if (status != 1) {
            return errorResult("高德 API 返回错误: " + json.getString("info"));
        }
        JSONArray pois = json.getJSONArray("pois");
        if (pois == null || pois.isEmpty()) {
            JSONObject empty = new JSONObject();
            empty.put("count", 0);
            empty.put("pois", new ArrayList<>());
            return JSON.toJSONString(empty);
        }

        List<JSONObject> poiList = new ArrayList<>();
        for (int i = 0; i < pois.size(); i++) {
            JSONObject poi = pois.getJSONObject(i);
            JSONObject item = new JSONObject();
            item.put("name", poi.getString("name"));
            item.put("address", poi.getString("address"));
            item.put("location", poi.getString("location"));
            item.put("tel", poi.getString("tel"));
            item.put("type", poi.getString("type"));
            item.put("distance", poi.getString("distance"));
            poiList.add(item);
        }

        JSONObject result = new JSONObject();
        result.put("count", poiList.size());
        result.put("pois", poiList);
        return JSON.toJSONString(result);
    }

    private String errorResult(String msg) {
        JSONObject error = new JSONObject();
        error.put("error", msg);
        return JSON.toJSONString(error);
    }
}
