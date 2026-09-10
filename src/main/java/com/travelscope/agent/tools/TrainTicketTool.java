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
 * 火车票查询工具（高德地图跨城公交路径规划 v5）
 * <p>
 * 高德 Web 服务没有独立的火车票接口，跨城火车方案通过
 * 「公交路径规划 v5」（/v5/direction/transit/integrated）获取：
 * 跨城场景下返回的换乘方案中包含火车段（railway / buslines 中的火车线路），
 * 携带车次名、出发/到达站点、出发/到达时间；show_fields=cost 时含参考票价。
 * </p>
 * <p>
 * 查询流程：
 *   1. 地理编码：出发城市 / 到达城市 → 经纬度 + adcode
 *   2. 跨城公交规划：传入双城坐标 + citycode + 出发日期
 *   3. 从换乘方案中提取火车段信息
 * </p>
 * 接口文档: https://lbs.amap.com/api/webservice/guide/api/newroute
 * </p>
 */
@Component
public class TrainTicketTool {

    private static final Logger log = LoggerFactory.getLogger(TrainTicketTool.class);

    /** 高德 v5 API 基础地址（AppProperties 中的 baseUrl 是 v3） */
    private static final String AMAP_V5_BASE = "https://restapi.amap.com/v5";

    private final AppProperties appProperties;
    private final OkHttpClient httpClient;

    public TrainTicketTool(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 查询两城市之间的火车方案
     *
     * @param originCity      出发城市名称（如 "北京"）
     * @param destinationCity 到达城市名称（如 "上海"）
     * @param date            出发日期，格式 yyyy-MM-dd（如 "2026-09-15"）
     * @return 火车方案列表 JSON（车次、站点、时间、参考票价）
     */
    @Tool(description = "查询两个城市之间的火车出行方案，包括车次名称、出发/到达车站、出发/到达时间、行程耗时和参考票价。用于跨城旅游的交通规划。")
    public String searchTrainTickets(
            @ToolParam(name = "originCity", description = "出发城市名称，如 '北京'、'杭州'")
            String originCity,
            @ToolParam(name = "destinationCity", description = "到达城市名称，如 '上海'、'成都'")
            String destinationCity,
            @ToolParam(name = "date", description = "出发日期，格式 yyyy-MM-dd，如 '2026-09-15'")
            String date
    ) {
        String apiKey = appProperties.getAmap().getWebApiKey();
        String baseUrl = appProperties.getAmap().getBaseUrl();

        if (apiKey == null || apiKey.isBlank()) {
            return errorResult("高德地图 API Key 未配置，请检查环境变量 AMAP_WEB_API_KEY");
        }
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return errorResult("日期格式不正确，请使用 yyyy-MM-dd 格式，如 2026-09-15");
        }

        log.info("查询火车方案: {} -> {}, date={}", originCity, destinationCity, date);

        // 第一步：地理编码双城
        JSONObject originGeo = geocodeCity(originCity, apiKey, baseUrl);
        if (originGeo == null) {
            return errorResult("未找到出发城市: " + originCity);
        }
        JSONObject destGeo = geocodeCity(destinationCity, apiKey, baseUrl);
        if (destGeo == null) {
            return errorResult("未找到到达城市: " + destinationCity);
        }

        // 第二步：跨城公交路径规划 v5
        HttpUrl url = HttpUrl.parse(AMAP_V5_BASE + "/direction/transit/integrated").newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("origin", originGeo.getString("location"))
                .addQueryParameter("destination", destGeo.getString("location"))
                .addQueryParameter("city1", originGeo.getString("adcode"))
                .addQueryParameter("city2", destGeo.getString("adcode"))
                .addQueryParameter("date", date)
                .addQueryParameter("time", "08:00")
                .addQueryParameter("strategy", "0")
                .addQueryParameter("show_fields", "cost")
                .addQueryParameter("output", "JSON")
                .build();

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return errorResult("高德 API 请求失败，HTTP " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "{}";
            return parseTrainRoutes(body, originCity, destinationCity, date);
        } catch (IOException e) {
            log.error("火车方案查询异常: {} -> {}", originCity, destinationCity, e);
            return errorResult("火车方案查询失败: " + e.getMessage());
        }
    }

    /** 地理编码：城市名 → 经纬度 + adcode */
    private JSONObject geocodeCity(String city, String apiKey, String baseUrl) {
        HttpUrl url = HttpUrl.parse(baseUrl + "/geocode/geo").newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("address", city)
                .addQueryParameter("output", "JSON")
                .build();

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("地理编码失败: city={}, HTTP {}", city, response.code());
                return null;
            }
            String body = response.body() != null ? response.body().string() : "{}";
            JSONObject json = JSON.parseObject(body);
            JSONArray geocodes = json.getJSONArray("geocodes");
            if (geocodes == null || geocodes.isEmpty()) {
                return null;
            }
            JSONObject geo = geocodes.getJSONObject(0);
            JSONObject result = new JSONObject();
            result.put("location", geo.getString("location"));
            // 优先 adcode（跨城规划 city1/city2 接受 adcode/citycode）
            String adcode = geo.getString("adcode");
            result.put("adcode", adcode != null && !adcode.isBlank() ? adcode : geo.getString("citycode"));
            return result;
        } catch (IOException e) {
            log.error("地理编码异常: city={}", city, e);
            return null;
        }
    }

    /** 解析跨城公交规划结果，提取火车段 */
    private String parseTrainRoutes(String body, String originCity, String destinationCity, String date) {
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
            return errorResult("未找到 " + originCity + " 到 " + destinationCity + " 的火车方案");
        }

        List<JSONObject> plans = new ArrayList<>();
        for (int i = 0; i < transits.size() && i < 5; i++) {
            JSONObject transit = transits.getJSONObject(i);
            JSONObject plan = new JSONObject();
            plan.put("duration", transit.getString("duration"));
            plan.put("distance", transit.getString("distance"));
            plan.put("walking_distance", transit.getString("walking_distance"));
            // show_fields=cost 时 transit 级含费用信息（字段名因版本而异，原样透传）
            Object cost = transit.get("cost");
            if (cost != null) {
                plan.put("cost", cost);
            }

            // 提取火车段（兼容 railway 对象与 bus.buslines 两种结构）
            List<JSONObject> trainSegments = new ArrayList<>();
            JSONArray segments = transit.getJSONArray("segments");
            if (segments != null) {
                for (int j = 0; j < segments.size(); j++) {
                    JSONObject seg = segments.getJSONObject(j);

                    JSONObject railway = seg.getJSONObject("railway");
                    if (railway != null && !railway.isEmpty()) {
                        trainSegments.add(parseRailwaySegment(railway));
                        continue;
                    }

                    JSONObject bus = seg.getJSONObject("bus");
                    if (bus != null) {
                        JSONArray buslines = bus.getJSONArray("buslines");
                        if (buslines != null) {
                            for (int k = 0; k < buslines.size(); k++) {
                                JSONObject line = buslines.getJSONObject(k);
                                String type = line.getString("type");
                                if (type != null && type.contains("火车")) {
                                    trainSegments.add(parseBuslineTrainSegment(line));
                                }
                            }
                        }
                    }
                }
            }
            plan.put("train_segments", trainSegments);
            plans.add(plan);
        }

        JSONObject result = new JSONObject();
        result.put("origin_city", originCity);
        result.put("destination_city", destinationCity);
        result.put("date", date);
        result.put("count", plans.size());
        result.put("plans", plans);
        result.put("notice", "票价为高德参考价，实际票价与余票请以 12306 为准");
        return JSON.toJSONString(result);
    }

    /** 解析 railway 结构的火车段 */
    private JSONObject parseRailwaySegment(JSONObject railway) {
        JSONObject seg = new JSONObject();
        seg.put("train_no", railway.getString("name"));
        seg.put("trip", railway.getString("trip"));
        seg.put("type", railway.getString("type"));
        JSONObject dep = railway.getJSONObject("departure_stop");
        if (dep != null) {
            seg.put("departure_station", dep.getString("name"));
            seg.put("departure_time", dep.getString("time"));
        }
        JSONObject arr = railway.getJSONObject("arrival_stop");
        if (arr != null) {
            seg.put("arrival_station", arr.getString("name"));
            seg.put("arrival_time", arr.getString("time"));
        }
        seg.put("via_stops", railway.getString("via_stops"));
        return seg;
    }

    /** 解析 buslines 中的火车线路段 */
    private JSONObject parseBuslineTrainSegment(JSONObject line) {
        JSONObject seg = new JSONObject();
        seg.put("train_no", line.getString("name"));
        seg.put("type", line.getString("type"));
        JSONObject dep = line.getJSONObject("departure_stop");
        if (dep != null) {
            seg.put("departure_station", dep.getString("name"));
        }
        JSONObject arr = line.getJSONObject("arrival_stop");
        if (arr != null) {
            seg.put("arrival_station", arr.getString("name"));
        }
        seg.put("departure_time", line.getString("start_time"));
        seg.put("arrival_time", line.getString("end_time"));
        seg.put("via_stops", line.getString("via_num"));
        return seg;
    }

    private String errorResult(String msg) {
        JSONObject error = new JSONObject();
        error.put("error", msg);
        return JSON.toJSONString(error);
    }
}
