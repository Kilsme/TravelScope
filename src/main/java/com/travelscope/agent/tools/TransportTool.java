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
 * 车票/交通查询工具（高德地图路线规划）
 * <p>
 * 通过高德地图 API 实现：
 * - 地理编码：地址 → 经纬度
 * - 驾车路线规划：两地间驾车距离和时间
 * - 公共交通路线规划：两地间公交/地铁方案
 * </p>
 */
@Component
public class TransportTool {

    private static final Logger log = LoggerFactory.getLogger(TransportTool.class);

    private final AppProperties appProperties;
    private final OkHttpClient httpClient;

    public TransportTool(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 查询两个地点之间的驾车路线
     *
     * @param origin      起点坐标（经纬度）
     * @param destination 终点坐标（经纬度）
     * @return 路线信息（距离、时间、路线描述）
     */
    @Tool(description = "查询两个地点之间的驾车路线，包括距离、预计时间、路线描述。用于旅游出行规划。")
    public String getDrivingRoute(
            @ToolParam(name = "origin", description = "起点经纬度坐标，格式: '经度,纬度'，如 '116.397428,39.90923'")
            String origin,
            @ToolParam(name = "destination", description = "终点经纬度坐标，格式: '经度,纬度'")
            String destination
    ) {
        String apiKey = appProperties.getAmap().getWebApiKey();
        String baseUrl = appProperties.getAmap().getBaseUrl();

        if (apiKey == null || apiKey.isBlank()) {
            return errorResult("高德地图 API Key 未配置");
        }

        log.info("驾车路线: origin={}, destination={}", origin, destination);

        HttpUrl url = HttpUrl.parse(baseUrl + "/direction/driving").newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("origin", origin)
                .addQueryParameter("destination", destination)
                .addQueryParameter("extensions", "base")
                .addQueryParameter("output", "JSON")
                .build();

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return errorResult("高德 API 请求失败，HTTP " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "{}";
            return parseDrivingRoute(body);
        } catch (IOException e) {
            log.error("驾车路线查询异常", e);
            return errorResult("驾车路线查询失败: " + e.getMessage());
        }
    }

    /**
     * 查询两个地点之间的公共交通路线
     *
     * @param origin      起点坐标
     * @param destination 终点坐标
     * @param city        城市名称
     * @return 公交/地铁路线信息
     */
    @Tool(description = "查询两个地点之间的公共交通路线（公交/地铁），包括换乘方案、预计时间。用于市内交通规划。")
    public String getTransitRoute(
            @ToolParam(name = "origin", description = "起点经纬度坐标，格式: '经度,纬度'")
            String origin,
            @ToolParam(name = "destination", description = "终点经纬度坐标，格式: '经度,纬度'")
            String destination,
            @ToolParam(name = "city", description = "所在城市名称，如 '北京'")
            String city
    ) {
        String apiKey = appProperties.getAmap().getWebApiKey();
        String baseUrl = appProperties.getAmap().getBaseUrl();

        if (apiKey == null || apiKey.isBlank()) {
            return errorResult("高德地图 API Key 未配置");
        }

        log.info("公交路线: origin={}, destination={}, city={}", origin, destination, city);

        HttpUrl url = HttpUrl.parse(baseUrl + "/direction/transit/integrated").newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("origin", origin)
                .addQueryParameter("destination", destination)
                .addQueryParameter("city", city)
                .addQueryParameter("citylimit", "true")
                .addQueryParameter("output", "JSON")
                .build();

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return errorResult("高德 API 请求失败，HTTP " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "{}";
            return parseTransitRoute(body);
        } catch (IOException e) {
            log.error("公交路线查询异常", e);
            return errorResult("公交路线查询失败: " + e.getMessage());
        }
    }

    /**
     * 地理编码：将地址转换为经纬度坐标
     *
     * @param address 地址文本（如 "北京市朝阳区望京SOHO"）
     * @return 经纬度坐标
     */
    @Tool(description = "将地址文本转换为经纬度坐标，用于后续的路线规划和周边搜索。")
    public String geocode(
            @ToolParam(name = "address", description = "地址文本，如 '北京市朝阳区望京SOHO'、'杭州西湖'")
            String address
    ) {
        String apiKey = appProperties.getAmap().getWebApiKey();
        String baseUrl = appProperties.getAmap().getBaseUrl();

        if (apiKey == null || apiKey.isBlank()) {
            return errorResult("高德地图 API Key 未配置");
        }

        log.info("地理编码: address={}", address);

        HttpUrl url = HttpUrl.parse(baseUrl + "/geocode/geo").newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("address", address)
                .addQueryParameter("output", "JSON")
                .build();

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return errorResult("高德 API 请求失败，HTTP " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "{}";
            return parseGeocode(body);
        } catch (IOException e) {
            log.error("地理编码异常", e);
            return errorResult("地理编码失败: " + e.getMessage());
        }
    }

    /** 解析驾车路线结果 */
    private String parseDrivingRoute(String body) {
        JSONObject json = JSON.parseObject(body);
        int status = json.getIntValue("status");
        if (status != 1) {
            return errorResult("高德 API 返回错误: " + json.getString("info"));
        }
        JSONObject route = json.getJSONObject("route");
        if (route == null) {
            return errorResult("未找到路线信息");
        }
        JSONArray paths = route.getJSONArray("paths");
        if (paths == null || paths.isEmpty()) {
            return errorResult("未找到驾车路线");
        }

        JSONObject path = paths.getJSONObject(0);
        JSONObject result = new JSONObject();
        result.put("distance", path.getString("distance"));
        result.put("duration", path.getString("duration"));
        result.put("strategy", path.getString("strategy"));
        result.put("tolls", path.getString("tolls"));

        // 提取路线步骤
        JSONArray steps = path.getJSONArray("steps");
        List<String> instructions = new ArrayList<>();
        if (steps != null) {
            for (int i = 0; i < steps.size() && i < 10; i++) {
                JSONObject step = steps.getJSONObject(i);
                instructions.add(step.getString("instruction"));
            }
        }
        result.put("steps", instructions);
        return JSON.toJSONString(result);
    }

    /** 解析公交路线结果 */
    private String parseTransitRoute(String body) {
        JSONObject json = JSON.parseObject(body);
        int status = json.getIntValue("status");
        if (status != 1) {
            return errorResult("高德 API 返回错误: " + json.getString("info"));
        }
        JSONObject route = json.getJSONObject("route");
        if (route == null) {
            return errorResult("未找到路线信息");
        }
        JSONArray transits = route.getJSONArray("transits");
        if (transits == null || transits.isEmpty()) {
            return errorResult("未找到公交路线");
        }

        List<JSONObject> routes = new ArrayList<>();
        for (int i = 0; i < transits.size() && i < 3; i++) {
            JSONObject transit = transits.getJSONObject(i);
            JSONObject r = new JSONObject();
            r.put("duration", transit.getString("duration"));
            r.put("distance", transit.getString("distance"));
            r.put("cost", transit.getString("cost"));
            r.put("walking_distance", transit.getString("walking_distance"));

            // 提取换乘段
            JSONArray segments = transit.getJSONArray("segments");
            List<String> segmentDescs = new ArrayList<>();
            if (segments != null) {
                for (int j = 0; j < segments.size(); j++) {
                    JSONObject seg = segments.getJSONObject(j);
                    JSONObject bus = seg.getJSONObject("bus");
                    if (bus != null) {
                        JSONArray busLines = bus.getJSONArray("buslines");
                        if (busLines != null && !busLines.isEmpty()) {
                            JSONObject line = busLines.getJSONObject(0);
                            segmentDescs.add(line.getString("name"));
                        }
                    }
                }
            }
            r.put("bus_lines", segmentDescs);
            routes.add(r);
        }

        JSONObject result = new JSONObject();
        result.put("count", routes.size());
        result.put("routes", routes);
        return JSON.toJSONString(result);
    }

    /** 解析地理编码结果 */
    private String parseGeocode(String body) {
        JSONObject json = JSON.parseObject(body);
        int status = json.getIntValue("status");
        if (status != 1) {
            return errorResult("高德 API 返回错误: " + json.getString("info"));
        }
        JSONArray geocodes = json.getJSONArray("geocodes");
        if (geocodes == null || geocodes.isEmpty()) {
            return errorResult("未找到地理编码结果");
        }

        JSONObject geo = geocodes.getJSONObject(0);
        JSONObject result = new JSONObject();
        result.put("location", geo.getString("location"));
        result.put("formatted_address", geo.getString("formatted_address"));
        result.put("province", geo.getString("province"));
        result.put("city", geo.getString("city"));
        result.put("district", geo.getString("district"));
        return JSON.toJSONString(result);
    }

    private String errorResult(String msg) {
        JSONObject error = new JSONObject();
        error.put("error", msg);
        return JSON.toJSONString(error);
    }
}
