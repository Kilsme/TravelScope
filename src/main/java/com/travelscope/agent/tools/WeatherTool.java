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

/**
 * 天气查询工具
 * <p>
 * 通过和风天气 API 查询指定城市的实时天气和天气预报。
 * 和风天气 API 分两步：
 *   1. 城市查询（GeoAPI）：城市名 → LocationID
 *   2. 天气查询：LocationID → 天气数据
 * 接口文档: https://dev.qweather.com/
 * </p>
 */
@Component
public class WeatherTool {

    private static final Logger log = LoggerFactory.getLogger(WeatherTool.class);

    private final AppProperties appProperties;
    private final OkHttpClient httpClient;

    public WeatherTool(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 查询指定城市的实时天气
     * <p>
     * 先通过 GeoAPI 查询城市 ID，再调用实时天气接口获取天气数据。
     * </p>
     *
     * @param city 城市名称（如 "北京"、"上海"）
     * @return 天气信息 JSON（温度、天气状况、风力、湿度等）
     */
    @Tool(description = "查询指定城市的实时天气信息，包括温度、天气状况、风力、湿度等。用于旅游出行前的天气了解。")
    public String getWeather(
            @ToolParam(name = "city", description = "城市名称，如 '北京'、'上海'、'杭州'")
            String city
    ) {
        String apiKey = appProperties.getWeather().getApiKey();
        String apiHost = appProperties.getWeather().getApiHost();

        if (apiKey == null || apiKey.isBlank()) {
            return errorResult("和风天气 API Key 未配置，请检查环境变量 WEATHER_API_KEY");
        }
        if (apiHost == null || apiHost.isBlank()) {
            return errorResult("和风天气 API Host 未配置，请检查环境变量 WEATHER_API_HOST");
        }

        log.info("查询天气: city={}, apiHost={}", city, apiHost);

        // 清理 Host 前缀（用户可能配置了 https:// 前缀）
        String cleanHost = apiHost.replaceFirst("^https?://", "");

        // 第一步：查询城市 LocationID
        GeoResult geo = resolveLocationId(city, apiKey, cleanHost);
        if (geo.locationId() == null) {
            return errorResult(geo.error());
        }

        // 第二步：查询实时天气
        HttpUrl url = HttpUrl.parse("https://" + cleanHost + "/v7/weather/now").newBuilder()
                .addQueryParameter("location", geo.locationId())
                .addQueryParameter("key", apiKey)
                .build();

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "{}";
            if (!response.isSuccessful()) {
                log.error("和风天气 API 请求失败: HTTP {}, body={}", response.code(), abbreviate(body));
                return errorResult("和风天气 API 请求失败，HTTP " + response.code() + "，" + abbreviate(body));
            }
            return parseWeatherNow(body, city);
        } catch (IOException e) {
            log.error("查询天气异常: city={}", city, e);
            return errorResult("查询天气失败: " + e.getMessage());
        }
    }

    /**
     * 查询指定城市未来几天的天气预报
     *
     * @param city 城市名称
     * @param days 预报天数（3/7/10/15/30）
     * @return 天气预报信息
     */
    @Tool(description = "查询指定城市未来几天的天气预报，用于旅游行程规划时参考。")
    public String getWeatherForecast(
            @ToolParam(name = "city", description = "城市名称，如 '北京'、'成都'")
            String city,
            @ToolParam(name = "days", description = "预报天数，可选: 3/7/10/15/30")
            int days
    ) {
        String apiKey = appProperties.getWeather().getApiKey();
        String apiHost = appProperties.getWeather().getApiHost();

        if (apiKey == null || apiKey.isBlank()) {
            return errorResult("和风天气 API Key 未配置");
        }
        if (apiHost == null || apiHost.isBlank()) {
            return errorResult("和风天气 API Host 未配置");
        }

        log.info("查询天气预报: city={}, days={}", city, days);

        // 清理 Host 前缀
        String cleanHost = apiHost.replaceFirst("^https?://", "");

        // 第一步：查询城市 LocationID
        GeoResult geo = resolveLocationId(city, apiKey, cleanHost);
        if (geo.locationId() == null) {
            return errorResult(geo.error());
        }

        // 第二步：查询天气预报
        int validDays = switch (days) {
            case 7 -> 7;
            case 10 -> 10;
            case 15 -> 15;
            case 30 -> 30;
            default -> 3;
        };

        HttpUrl url = HttpUrl.parse("https://" + cleanHost + "/v7/weather/" + validDays + "d").newBuilder()
                .addQueryParameter("location", geo.locationId())
                .addQueryParameter("key", apiKey)
                .build();

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "{}";
            if (!response.isSuccessful()) {
                log.error("和风天气预报 API 请求失败: HTTP {}, body={}", response.code(), abbreviate(body));
                return errorResult("和风天气 API 请求失败，HTTP " + response.code() + "，" + abbreviate(body));
            }
            return parseWeatherForecast(body, city, validDays);
        } catch (IOException e) {
            log.error("查询天气预报异常: city={}", city, e);
            return errorResult("查询天气预报失败: " + e.getMessage());
        }
    }

    /** 城市查询结果：locationId 与 error 必有一者为 null */
    private record GeoResult(String locationId, String error) {
    }

    /**
     * 推导 GeoAPI 的 base URL。
     * 和风新版专属域名（xxx.re.qweatherapi.com）下 GeoAPI 与天气 API 同域名，
     * 路径需加 /geo 前缀（/geo/v2/city/lookup）；遗留公共域名则走独立的 geoapi.qweather.com。
     */
    static String resolveGeoApiBase(String apiHost) {
        if (apiHost.endsWith(".re.qweatherapi.com")) {
            return "https://" + apiHost + "/geo";
        }
        return "https://geoapi.qweather.com";
    }

    /** 查询城市 LocationID */
    private GeoResult resolveLocationId(String city, String apiKey, String apiHost) {
        HttpUrl url = HttpUrl.parse(resolveGeoApiBase(apiHost) + "/v2/city/lookup").newBuilder()
                .addQueryParameter("location", city)
                .addQueryParameter("key", apiKey)
                .build();

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                // 和风错误体里有具体错误码（401=key 无效、403=无权限、404=路径/域名错误），必须透出
                log.error("城市查询失败: HTTP {}, body={}", response.code(), abbreviate(body));
                return new GeoResult(null, "城市查询失败，和风 API 返回 HTTP " + response.code() + "，" + abbreviate(body));
            }
            JSONObject json = JSON.parseObject(body);
            JSONArray locations = json.getJSONArray("location");
            if (locations != null && !locations.isEmpty()) {
                return new GeoResult(locations.getJSONObject(0).getString("id"), null);
            }
            String code = json.getString("code");
            String detail = code != null && !"200".equals(code) ? "（和风 code=" + code + "）" : "";
            return new GeoResult(null, "未找到城市: " + city + detail);
        } catch (IOException e) {
            log.error("城市查询异常: city={}", city, e);
            return new GeoResult(null, "城市查询失败: " + e.getMessage());
        }
    }

    /** 截断外部响应体，避免过长的 HTML 错误页进入日志和 LLM 上下文 */
    private static String abbreviate(String body) {
        if (body == null || body.isBlank()) {
            return "无响应体";
        }
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }

    /** 解析实时天气结果 */
    private String parseWeatherNow(String body, String city) {
        JSONObject json = JSON.parseObject(body);
        String code = json.getString("code");
        if (!"200".equals(code)) {
            return errorResult("和风天气 API 返回错误: code=" + code);
        }
        JSONObject now = json.getJSONObject("now");
        if (now == null) {
            return errorResult("未找到天气数据");
        }

        JSONObject result = new JSONObject();
        result.put("city", city);
        result.put("temp", now.getString("temp"));
        result.put("feels_like", now.getString("feelsLike"));
        result.put("text", now.getString("text"));
        result.put("wind_dir", now.getString("windDir"));
        result.put("wind_scale", now.getString("windScale"));
        result.put("wind_speed", now.getString("windSpeed"));
        result.put("humidity", now.getString("humidity"));
        result.put("pressure", now.getString("pressure"));
        result.put("visibility", now.getString("vis"));
        result.put("cloud", now.getString("cloud"));
        result.put("dew", now.getString("dew"));
        result.put("obs_time", now.getString("obsTime"));
        return JSON.toJSONString(result);
    }

    /** 解析天气预报结果 */
    private String parseWeatherForecast(String body, String city, int days) {
        JSONObject json = JSON.parseObject(body);
        String code = json.getString("code");
        if (!"200".equals(code)) {
            return errorResult("和风天气 API 返回错误: code=" + code);
        }
        JSONArray daily = json.getJSONArray("daily");
        if (daily == null || daily.isEmpty()) {
            return errorResult("未找到天气预报数据");
        }

        java.util.List<JSONObject> forecasts = new java.util.ArrayList<>();
        for (int i = 0; i < daily.size(); i++) {
            JSONObject d = daily.getJSONObject(i);
            JSONObject f = new JSONObject();
            f.put("date", d.getString("fxDate"));
            f.put("week", d.getString("week"));
            f.put("text_day", d.getString("textDay"));
            f.put("text_night", d.getString("textNight"));
            f.put("temp_max", d.getString("tempMax"));
            f.put("temp_min", d.getString("tempMin"));
            f.put("wind_dir_day", d.getString("windDirDay"));
            f.put("wind_scale_day", d.getString("windScaleDay"));
            f.put("humidity", d.getString("humidity"));
            f.put("uv_index", d.getString("uvIndex"));
            forecasts.add(f);
        }

        JSONObject result = new JSONObject();
        result.put("city", city);
        result.put("days", days);
        result.put("forecasts", forecasts);
        return JSON.toJSONString(result);
    }

    private String errorResult(String msg) {
        JSONObject error = new JSONObject();
        error.put("error", msg);
        return JSON.toJSONString(error);
    }
}
