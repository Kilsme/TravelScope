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
 * 飞机票/机场查询工具（高德地图）
 * <p>
 * 能力边界说明：高德 Web 服务 API 不提供实时航班票价与余票数据，
 * 本工具基于高德能力提供：
 * - 城市机场查询（POI 类型码 150200 飞机场）：机场名称、地址、经纬度
 * - 航线信息查询：查询出发/到达城市的机场组合，返回结构化参考信息，
 *   并在结果中明确标注票价需以订票平台为准
 * </p>
 * <p>
 * 后续如需实时票价/余票，可在此工具内扩展接入航旅类 API（如航旅纵横、携程开放平台），
 * 对 Agent 侧保持工具签名不变。
 * </p>
 */
@Component
public class FlightTicketTool {

    private static final Logger log = LoggerFactory.getLogger(FlightTicketTool.class);

    /** POI 类型码：飞机场 */
    private static final String POI_TYPE_AIRPORT = "150200";

    private final AppProperties appProperties;
    private final OkHttpClient httpClient;

    public FlightTicketTool(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 查询指定城市的机场信息
     *
     * @param city 城市名称（如 "北京"、"上海"）
     * @return 机场列表 JSON（名称、地址、经纬度）
     */
    @Tool(description = "查询指定城市的机场信息，包括机场名称、地址、经纬度。用于确认城市的起降机场。")
    public String searchAirports(
            @ToolParam(name = "city", description = "城市名称，如 '北京'、'上海'、'成都'")
            String city
    ) {
        String apiKey = appProperties.getAmap().getWebApiKey();
        String baseUrl = appProperties.getAmap().getBaseUrl();

        if (apiKey == null || apiKey.isBlank()) {
            return errorResult("高德地图 API Key 未配置，请检查环境变量 AMAP_WEB_API_KEY");
        }

        log.info("查询机场: city={}", city);

        HttpUrl url = HttpUrl.parse(baseUrl + "/place/text").newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("keywords", "机场")
                .addQueryParameter("city", city)
                .addQueryParameter("citylimit", "true")
                .addQueryParameter("types", POI_TYPE_AIRPORT)
                .addQueryParameter("offset", "10")
                .addQueryParameter("page", "1")
                .addQueryParameter("output", "JSON")
                .build();

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return errorResult("高德 API 请求失败，HTTP " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "{}";
            return parseAirportResults(body, city);
        } catch (IOException e) {
            log.error("机场查询异常: city={}", city, e);
            return errorResult("机场查询失败: " + e.getMessage());
        }
    }

    /**
     * 查询两城市之间的飞机出行方案（机场组合 + 参考信息）
     *
     * @param originCity      出发城市名称
     * @param destinationCity 到达城市名称
     * @param date            出发日期，格式 yyyy-MM-dd
     * @return 航线参考信息 JSON（双城机场、说明）
     */
    @Tool(description = "查询两个城市之间的飞机出行方案，返回出发/到达城市的机场组合与航线参考信息。注意：本工具不提供实时票价，票价需以航司或订票平台为准。")
    public String searchFlightTickets(
            @ToolParam(name = "originCity", description = "出发城市名称，如 '北京'")
            String originCity,
            @ToolParam(name = "destinationCity", description = "到达城市名称，如 '上海'")
            String destinationCity,
            @ToolParam(name = "date", description = "出发日期，格式 yyyy-MM-dd，如 '2026-09-15'")
            String date
    ) {
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return errorResult("日期格式不正确，请使用 yyyy-MM-dd 格式，如 2026-09-15");
        }

        log.info("查询飞机方案: {} -> {}, date={}", originCity, destinationCity, date);

        // 查询双城机场
        String originAirportsJson = searchAirports(originCity);
        String destAirportsJson = searchAirports(destinationCity);

        JSONObject originAirports = JSON.parseObject(originAirportsJson);
        JSONObject destAirports = JSON.parseObject(destAirportsJson);

        if (originAirports.containsKey("error")) {
            return errorResult("出发城市机场查询失败: " + originAirports.getString("error"));
        }
        if (destAirports.containsKey("error")) {
            return errorResult("到达城市机场查询失败: " + destAirports.getString("error"));
        }

        JSONObject result = new JSONObject();
        result.put("origin_city", originCity);
        result.put("destination_city", destinationCity);
        result.put("date", date);
        result.put("departure_airports", originAirports.getJSONArray("airports"));
        result.put("arrival_airports", destAirports.getJSONArray("airports"));
        result.put("notice", "高德地图暂不支持实时航班票价与余票查询。已提供双城机场信息，"
                + "航班号、票价、余票请以航司官网或订票平台（如携程/飞猪/航旅纵横）查询结果为准，"
                + "行程中请将机票价格标注为「待确认」");
        return JSON.toJSONString(result);
    }

    /** 解析机场搜索结果 */
    private String parseAirportResults(String body, String city) {
        JSONObject json = JSON.parseObject(body);
        int status = json.getIntValue("status");
        if (status != 1) {
            return errorResult("高德 API 返回错误: " + json.getString("info"));
        }
        JSONArray pois = json.getJSONArray("pois");
        if (pois == null || pois.isEmpty()) {
            JSONObject empty = new JSONObject();
            empty.put("city", city);
            empty.put("count", 0);
            empty.put("airports", new ArrayList<>());
            return JSON.toJSONString(empty);
        }

        List<JSONObject> airports = new ArrayList<>();
        for (int i = 0; i < pois.size(); i++) {
            JSONObject poi = pois.getJSONObject(i);
            JSONObject item = new JSONObject();
            item.put("name", poi.getString("name"));
            item.put("address", poi.getString("address"));
            item.put("location", poi.getString("location"));
            airports.add(item);
        }

        JSONObject result = new JSONObject();
        result.put("city", city);
        result.put("count", airports.size());
        result.put("airports", airports);
        return JSON.toJSONString(result);
    }

    private String errorResult(String msg) {
        JSONObject error = new JSONObject();
        error.put("error", msg);
        return JSON.toJSONString(error);
    }
}
