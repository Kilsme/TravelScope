package com.travelscope.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.travelscope.config.AppProperties;
import com.travelscope.tool.flyai.FlyaiCliTool;
import io.agentscope.core.agent.RuntimeContext;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 景点搜索工具（searchPois 双源：飞猪 flyai + 高德 POI，2026-10-17 飞猪比赛接入）
 * <p>
 * searchPois（城市景点初查统一入口）：虚拟线程并行调 flyai search-poi 与高德
 * /place/text，结果按名归一去重（飞猪优先——带景区等级/榜单/票务/预订链接），
 * 每条标 source（飞猪/高德）供前端展示数据来源；单源失败自动降级。
 * searchNearbyAttractions（周边检索）保持高德——飞猪无周边检索能力。
 * </p>
 */
@Component
public class AttractionTool {

    private static final Logger log = LoggerFactory.getLogger(AttractionTool.class);

    /** POI 类型码：风景名胜 */
    private static final String POI_TYPE_SCENIC = "110000";

    /** 数据来源标签 */
    public static final String SOURCE_FLIGGY = "飞猪";
    public static final String SOURCE_AMAP = "高德";

    private final AppProperties appProperties;
    private final FlyaiCliTool flyaiCliTool;
    private final OkHttpClient httpClient;

    public AttractionTool(AppProperties appProperties, FlyaiCliTool flyaiCliTool) {
        this.appProperties = appProperties;
        this.flyaiCliTool = flyaiCliTool;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 搜索指定城市的景点（双源：飞猪 + 高德，结果合并去重并标注来源）
     *
     * @param city     城市名称（如 "北京"、"杭州"）
     * @param keyword  搜索关键词（景点名、主题等，可为空）
     * @param pageSize 返回结果数量
     * @return 景点列表 JSON 字符串（attractions[] 每条含 source 字段）
     */
    @Tool(description = "搜索指定城市的景点 POI（searchPois，城市景点初查统一入口），"
            + "包括景点名称、地址、经纬度、评分等。用于旅游行程的景点初查与选择。"
            + "结果来自飞猪与高德双数据源，每条数据标注来源（飞猪/高德）。")
    public String searchPois(
            @ToolParam(name = "city", description = "城市名称，如 '北京'、'杭州'、'成都'")
            String city,
            @ToolParam(name = "keyword", description = "搜索关键词，如 '西湖'、'博物馆'、'古镇'，传空字符串则搜索城市热门景点")
            String keyword,
            @ToolParam(name = "pageSize", description = "返回结果数量，建议5-20")
            int pageSize,
            RuntimeContext ctx
    ) {
        log.info("搜索景点POI（双源）: city={}, keyword={}, pageSize={}", city, keyword, pageSize);
        boolean flyaiEnabled = appProperties.getFlyai().isEnabled();
        boolean amapKeyPresent = appProperties.getAmap().getWebApiKey() != null
                && !appProperties.getAmap().getWebApiKey().isBlank();

        if (!flyaiEnabled && !amapKeyPresent) {
            return errorResult("景点数据源均不可用（飞猪已禁用且高德 Key 未配置）");
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<List<JSONObject>> flyaiFuture = flyaiEnabled
                    ? CompletableFuture.supplyAsync(() -> fetchFlyaiPois(city, keyword), executor)
                    : CompletableFuture.completedFuture(List.of());
            CompletableFuture<List<JSONObject>> amapFuture = amapKeyPresent
                    ? CompletableFuture.supplyAsync(() -> fetchAmapPois(city, keyword, pageSize), executor)
                    : CompletableFuture.completedFuture(List.of());

            List<JSONObject> flyaiPois = flyaiFuture.join();
            List<JSONObject> amapPois = amapFuture.join();
            List<JSONObject> merged = mergePois(flyaiPois, amapPois, pageSize);

            if (merged.isEmpty()) {
                return errorResult("飞猪与高德均未查到景点（city=" + city + " keyword=" + keyword + "）");
            }
            // v5-F2 图片透传：ctx 有收集器（非 PLANNING 轮）时把 imageUrl 收进数组，收尾代码组装图集
            ImageCollector.collectFrom(ctx, merged);
            JSONObject result = new JSONObject();
            result.put("count", merged.size());
            result.put("attractions", merged);
            result.put("sources", Map.of("飞猪", flyaiPois.size(), "高德", amapPois.size()));
            return JSON.toJSONString(result);
        }
    }

    /** 程序内调用重载（PoiRetrievalService 硬补全/单测等无收集器场景）——委托 4 参主方法 */
    public String searchPois(String city, String keyword, int pageSize) {
        return searchPois(city, keyword, pageSize, null);
    }

    // ==================== 飞猪源 ====================

    /** 飞猪 POI 条目 → 统一结构（含 source；失败/空返回空列表降级） */
    private List<JSONObject> fetchFlyaiPois(String city, String keyword) {
        try {
            JSONObject response = flyaiCliTool.searchPoi(city, keyword);
            if (!FlyaiCliTool.hasItems(response)) {
                log.info("飞猪景点查询无结果或失败（降级高德）: {}", response.getString("error"));
                return List.of();
            }
            List<JSONObject> pois = new ArrayList<>();
            for (Object item : FlyaiCliTool.itemListOf(response)) {
                JSONObject raw = (JSONObject) item;
                JSONObject poi = new JSONObject();
                poi.put("sourceId", raw.getString("id"));
                poi.put("name", raw.getString("name"));
                poi.put("address", raw.getString("address"));
                poi.put("location", raw.getString("longitude") + "," + raw.getString("latitude"));
                poi.put("tel", null);
                poi.put("type", raw.getString("category"));
                poi.put("rating", raw.getString("poiLevel"));
                poi.put("listRank", raw.getString("listRank"));
                poi.put("freeStatus", raw.getString("freePoiStatus"));
                JSONObject ticket = raw.getJSONObject("ticketInfo");
                if (ticket != null) {
                    poi.put("ticketPrice", ticket.getString("price"));
                    poi.put("ticketName", ticket.getString("ticketName"));
                }
                poi.put("jumpUrl", raw.getString("jumpUrl"));
                poi.put("imageUrl", ImageCollector.flyaiImageUrl(raw));
                poi.put("source", SOURCE_FLIGGY);
                pois.add(poi);
            }
            return pois;
        } catch (Exception e) {
            log.warn("飞猪景点查询异常（降级高德）: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== 高德云（原实现迁入） ====================

    private List<JSONObject> fetchAmapPois(String city, String keyword, int pageSize) {
        String apiKey = appProperties.getAmap().getWebApiKey();
        String baseUrl = appProperties.getAmap().getBaseUrl();
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
                log.warn("高德景点查询 HTTP {}（降级飞猪）", response.code());
                return List.of();
            }
            return parseAmapPoiList(response.body() != null ? response.body().string() : "{}");
        } catch (IOException e) {
            log.warn("高德景点查询异常（降级飞猪）: {}", e.getMessage());
            return List.of();
        }
    }

    /** 高德 POI JSON → 统一结构列表（含 source） */
    private List<JSONObject> parseAmapPoiList(String body) {
        JSONObject json = JSON.parseObject(body);
        if (json.getIntValue("status") != 1) {
            log.warn("高德景点查询返回错误（降级飞猪）: {}", json.getString("info"));
            return List.of();
        }
        JSONArray pois = json.getJSONArray("pois");
        if (pois == null || pois.isEmpty()) {
            return List.of();
        }
        List<JSONObject> attractions = new ArrayList<>();
        for (int i = 0; i < pois.size(); i++) {
            JSONObject poi = pois.getJSONObject(i);
            JSONObject item = new JSONObject();
            item.put("sourceId", poi.getString("id"));
            item.put("name", poi.getString("name"));
            item.put("address", poi.getString("address"));
            item.put("location", poi.getString("location"));
            item.put("tel", poi.getString("tel"));
            item.put("type", poi.getString("type"));
            JSONObject bizExt = poi.getJSONObject("biz_ext");
            if (bizExt != null) {
                item.put("rating", bizExt.getString("rating"));
                item.put("cost", bizExt.getString("cost"));
            }
            item.put("imageUrl", ImageCollector.normalizeUrl(ImageCollector.amapPhotoUrl(poi)));
            item.put("source", SOURCE_AMAP);
            attractions.add(item);
        }
        return attractions;
    }

    // ==================== 双源合并（纯函数，可单测） ====================

    /**
     * 合并两源景点：按名归一去重（括号后缀归一），飞猪优先（带等级/榜单/票务/预订链接），
     * 截断 pageSize。每条已带 source 标注。
     */
    static List<JSONObject> mergePois(List<JSONObject> flyaiPois,
                                      List<JSONObject> amapPois, int pageSize) {
        Map<String, JSONObject> byName = new LinkedHashMap<>();
        for (JSONObject p : flyaiPois) {
            byName.putIfAbsent(HotelTool.normalizeName(p.getString("name")), p);
        }
        for (JSONObject p : amapPois) {
            byName.putIfAbsent(HotelTool.normalizeName(p.getString("name")), p);
        }
        return byName.values().stream().limit(Math.max(pageSize, 1)).toList();
    }

    /**
     * 搜索指定位置周边的景点（保持高德——飞猪无周边检索能力）
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
            int pageSize,
            RuntimeContext ctx
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
            return parseNearbyResults(body, ctx);
        } catch (IOException e) {
            log.error("周边景点搜索异常: location={}", location, e);
            return errorResult("周边景点搜索失败: " + e.getMessage());
        }
    }

    /** 程序内调用重载（无收集器场景）——委托 4 参主方法 */
    public String searchNearbyAttractions(String location, int radius, int pageSize) {
        return searchNearbyAttractions(location, radius, pageSize, null);
    }

    /** 解析周边景点结果（含 distance 字段；ctx 有收集器时同步收集 imageUrl） */
    private String parseNearbyResults(String body, RuntimeContext ctx) {
        JSONObject json = JSON.parseObject(body);
        if (json.getIntValue("status") != 1) {
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
            item.put("sourceId", poi.getString("id"));
            item.put("name", poi.getString("name"));
            item.put("address", poi.getString("address"));
            item.put("location", poi.getString("location"));
            item.put("tel", poi.getString("tel"));
            item.put("type", poi.getString("type"));
            item.put("distance", poi.getString("distance"));
            item.put("imageUrl", ImageCollector.normalizeUrl(ImageCollector.amapPhotoUrl(poi)));
            attractions.add(item);
        }
        ImageCollector.collectFrom(ctx, attractions);
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
