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
 * 酒店搜索工具（双源：飞猪 flyai + 高德 POI，2026-10-17 飞猪比赛接入）
 * <p>
 * 统一入口内双源合并：虚拟线程并行调 flyai search-hotel 与高德 /place/text，
 * 结果按名归一去重（飞猪优先——带价格/星级/预订链接），每条数据标 source
 * （飞猪/高德）供前端展示数据来源；单源失败自动降级为另一源，双源皆空才报错。
 * </p>
 */
@Component
public class HotelTool {

    private static final Logger log = LoggerFactory.getLogger(HotelTool.class);

    /** POI 类型码：住宿服务 */
    private static final String POI_TYPE_ACCOMMODATION = "100000";

    /** 数据来源标签 */
    public static final String SOURCE_FLIGGY = "飞猪";
    public static final String SOURCE_AMAP = "高德";

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
    private final FlyaiCliTool flyaiCliTool;
    private final OkHttpClient httpClient;

    public HotelTool(AppProperties appProperties, FlyaiCliTool flyaiCliTool) {
        this.appProperties = appProperties;
        this.flyaiCliTool = flyaiCliTool;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 搜索指定城市的酒店（双源：飞猪 + 高德，结果合并去重并标注来源）
     *
     * @param city     城市名称（如 "北京"、"杭州"）
     * @param keyword  搜索关键词（酒店名、品牌等，可为空）
     * @param pageSize 返回结果数量
     * @return 酒店列表 JSON 字符串（hotels[] 每条含 source 字段）
     */
    @Tool(description = "搜索指定城市的酒店信息，包括酒店名称、地址、经纬度、联系电话等。用于旅游住宿规划。"
            + "结果来自飞猪与高德双数据源，每条数据标注来源（飞猪/高德）。")
    public String searchHotels(
            @ToolParam(name = "city", description = "城市名称，如 '北京'、'杭州'")
            String city,
            @ToolParam(name = "keyword", description = "搜索关键词，如酒店名或品牌名称，传空字符串则搜索全部酒店")
            String keyword,
            @ToolParam(name = "pageSize", description = "返回结果数量，建议5-20")
            int pageSize,
            RuntimeContext ctx
    ) {
        log.info("搜索酒店（双源）: city={}, keyword={}, pageSize={}", city, keyword, pageSize);
        boolean flyaiEnabled = appProperties.getFlyai().isEnabled();
        boolean amapKeyPresent = appProperties.getAmap().getWebApiKey() != null
                && !appProperties.getAmap().getWebApiKey().isBlank();

        if (!flyaiEnabled && !amapKeyPresent) {
            return errorResult("酒店数据源均不可用（飞猪已禁用且高德 Key 未配置）");
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<List<JSONObject>> flyaiFuture = flyaiEnabled
                    ? CompletableFuture.supplyAsync(() -> fetchFlyaiHotels(city, keyword), executor)
                    : CompletableFuture.completedFuture(List.of());
            CompletableFuture<List<JSONObject>> amapFuture = amapKeyPresent
                    ? CompletableFuture.supplyAsync(() -> fetchAmapHotels(city, keyword, pageSize), executor)
                    : CompletableFuture.completedFuture(List.of());

            List<JSONObject> flyaiHotels = flyaiFuture.join();
            List<JSONObject> amapHotels = amapFuture.join();
            List<JSONObject> merged = mergeHotels(flyaiHotels, amapHotels, pageSize);

            if (merged.isEmpty()) {
                return errorResult("飞猪与高德均未查到酒店（city=" + city + " keyword=" + keyword + "）");
            }
            // v5-F2 图片透传：ctx 有收集器（非 PLANNING 轮）时把 imageUrl 收进数组，收尾代码组装图集
            ImageCollector.collectFrom(ctx, merged);
            JSONObject result = new JSONObject();
            result.put("count", merged.size());
            result.put("hotels", merged);
            result.put("sources", Map.of("飞猪", flyaiHotels.size(), "高德", amapHotels.size()));
            return JSON.toJSONString(result);
        }
    }

    /** 程序内调用重载（PoiRetrievalService/单测等无收集器场景）——委托 4 参主方法 */
    public String searchHotels(String city, String keyword, int pageSize) {
        return searchHotels(city, keyword, pageSize, null);
    }

    // ==================== 飞猪源 ====================

    /** 飞猪酒店条目 → 统一结构（含 source 标注；失败/空返回空列表降级） */
    private List<JSONObject> fetchFlyaiHotels(String city, String keyword) {
        try {
            JSONObject response = flyaiCliTool.searchHotel(city, keyword, null);
            if (!FlyaiCliTool.hasItems(response)) {
                log.info("飞猪酒店查询无结果或失败（降级高德）: {}", response.getString("error"));
                return List.of();
            }
            List<JSONObject> hotels = new ArrayList<>();
            for (Object item : FlyaiCliTool.itemListOf(response)) {
                JSONObject raw = (JSONObject) item;
                JSONObject hotel = new JSONObject();
                hotel.put("name", raw.getString("name"));
                hotel.put("address", raw.getString("address"));
                hotel.put("location", raw.getString("longitude") + "," + raw.getString("latitude"));
                hotel.put("tel", null);
                hotel.put("type", raw.getString("star"));
                hotel.put("price", raw.getString("price"));
                hotel.put("score", raw.getString("score"));
                hotel.put("detailUrl", raw.getString("detailUrl"));
                hotel.put("imageUrl", ImageCollector.flyaiImageUrl(raw));
                hotel.put("source", SOURCE_FLIGGY);
                hotels.add(hotel);
            }
            return hotels;
        } catch (Exception e) {
            log.warn("飞猪酒店查询异常（降级高德）: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== 高德云（原实现迁入） ====================

    private List<JSONObject> fetchAmapHotels(String city, String keyword, int pageSize) {
        String apiKey = appProperties.getAmap().getWebApiKey();
        String baseUrl = appProperties.getAmap().getBaseUrl();
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
                log.warn("高德酒店查询 HTTP {}（降级飞猪）", response.code());
                return List.of();
            }
            String body = response.body() != null ? response.body().string() : "{}";
            JSONObject json = JSON.parseObject(body);
            if (json.getIntValue("status") != 1) {
                log.warn("高德酒店查询返回错误（降级飞猪）: {}", json.getString("info"));
                return List.of();
            }
            JSONArray pois = json.getJSONArray("pois");
            if (pois == null || pois.isEmpty()) {
                return List.of();
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
                hotel.put("imageUrl", ImageCollector.normalizeUrl(ImageCollector.amapPhotoUrl(poi)));
                hotel.put("source", SOURCE_AMAP);
                hotels.add(hotel);
            }
            return hotels;
        } catch (IOException e) {
            log.warn("高德酒店查询异常（降级飞猪）: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== 双源合并（纯函数，可单测） ====================

    /**
     * 合并两源酒店：按名归一去重（括号后缀归一），飞猪优先（带价格/星级/预订链接），
     * 截断 pageSize。每条已带 source 标注。
     */
    static List<JSONObject> mergeHotels(List<JSONObject> flyaiHotels,
                                        List<JSONObject> amapHotels, int pageSize) {
        Map<String, JSONObject> byName = new LinkedHashMap<>();
        for (JSONObject h : flyaiHotels) {
            byName.putIfAbsent(normalizeName(h.getString("name")), h);
        }
        for (JSONObject h : amapHotels) {
            byName.putIfAbsent(normalizeName(h.getString("name")), h);
        }
        return byName.values().stream().limit(Math.max(pageSize, 1)).toList();
    }

    /** 酒店名归一：去括号及内容（「杭州某酒店(西湖店)」→「杭州某酒店」），供跨源去重 */
    static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("[（(][^）)]*[）)]", "").trim();
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
            int radius,
            RuntimeContext ctx
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
            return parsePoiResults(body, ctx);
        } catch (IOException e) {
            log.error("周边搜索异常: location={}", location, e);
            return errorResult("周边搜索失败: " + e.getMessage());
        }
    }

    /** 程序内调用重载（无收集器场景）——委托 4 参主方法 */
    public String searchNearbyPois(String location, String type, int radius) {
        return searchNearbyPois(location, type, radius, null);
    }

    /** 解析周边搜索结果（ctx 有收集器时同步收集 imageUrl——餐馆图集的代码组装数据源） */
    private String parsePoiResults(String body, RuntimeContext ctx) {
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
            item.put("imageUrl", ImageCollector.normalizeUrl(ImageCollector.amapPhotoUrl(poi)));
            poiList.add(item);
        }
        ImageCollector.collectFrom(ctx, poiList);

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
