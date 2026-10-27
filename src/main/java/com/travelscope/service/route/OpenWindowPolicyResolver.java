package com.travelscope.service.route;

import com.travelscope.service.route.dto.OpenWindowPolicy;

import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将原始开放时间和 POI 语义解析为显式开放状态，不调用日出日落服务。 */
public final class OpenWindowPolicyResolver {
    private static final Pattern WINDOW = Pattern.compile("([01]?\\d|2[0-3]):([0-5]\\d)\\s*[-~至到]\\s*([01]?\\d|2[0-3]):([0-5]\\d)");

    public OpenWindowPolicy resolve(String poiName, String openHours, String source) {
        String origin = source == null || source.isBlank() ? "UNKNOWN" : source;
        Instant fetchedAt = Instant.now();
        String raw = openHours == null ? "" : openHours.trim();
        if (is24Hours(raw)) {
            return policy(OpenWindowPolicy.OpenStatus.KNOWN_24H, "00:00", "23:59", origin, fetchedAt);
        }
        Matcher matcher = WINDOW.matcher(raw);
        if (matcher.find()) {
            return policy(OpenWindowPolicy.OpenStatus.KNOWN_OPEN,
                    pad(matcher.group(1)) + ":" + matcher.group(2),
                    pad(matcher.group(3)) + ":" + matcher.group(4), origin, fetchedAt);
        }
        if (isDaylightLandscape(poiName) && !isNightPoi(poiName)) {
            // 本轮不用日出日落 API，使用保守固定白昼窗口并显式标记推导状态。
            return policy(OpenWindowPolicy.OpenStatus.DAYLIGHT_ONLY,
                    "08:00", "18:00", origin, fetchedAt);
        }
        return new OpenWindowPolicy(OpenWindowPolicy.OpenStatus.UNKNOWN, List.of(), origin, fetchedAt);
    }

    private static OpenWindowPolicy policy(OpenWindowPolicy.OpenStatus status, String start, String end,
                                           String source, Instant fetchedAt) {
        return new OpenWindowPolicy(status, List.of(new OpenWindowPolicy.TimeWindow(start, end)), source, fetchedAt);
    }

    private static boolean is24Hours(String value) {
        String compact = value.replace(" ", "");
        return compact.contains("24小时") || compact.contains("全天开放") || compact.equals("全天")
                || compact.matches("00:00[-~至到]24:00");
    }

    private static boolean isDaylightLandscape(String name) {
        return containsAny(name, "溪", "瀑布", "峡谷", "山", "森林", "湿地", "草原", "沙漠",
                "海滩", "海岛", "湖", "公园", "花海", "古道", "栈道", "自然保护区");
    }

    private static boolean isNightPoi(String name) {
        return containsAny(name, "夜景", "夜游", "灯光", "灯会", "不夜城", "夜市", "夜间", "夜场");
    }

    private static boolean containsAny(String value, String... tokens) {
        if (value == null) return false;
        for (String token : tokens) if (value.contains(token)) return true;
        return false;
    }

    private static String pad(String hour) { return hour.length() == 1 ? "0" + hour : hour; }
}
