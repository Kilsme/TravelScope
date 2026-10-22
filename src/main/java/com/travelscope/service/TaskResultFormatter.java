package com.travelscope.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 任务结果缓存内容 → 用户可读摘要的代码格式化器（2026-10-20，修 raw JSON 上屏）。
 * <p>
 * 背景：记账去 LLM 化（10e9a22）后 BookkeepingMiddleware 把工具返回原文（飞猪/高德 JSON）
 * 直接登记进 {@link TaskResultCache}，而 {@link ItineraryAssembler} 的交通/住宿/天气三节
 * 按「摘要文本」语义原样拼接——缓存内容从此直通用户屏幕，append 多段的分隔元信息头行
 * （# task_id=… # registered_at=…）也一并泄漏（get() 只剥首行头）。
 * </p>
 * <p>
 * 本器在组装边界统一把缓存原文格式化为 markdown 摘要：识别对应工具 JSON 结构则渲染
 * 表格/列表；结构不认识（12306/飞常准 MCP 文本、工具改版）则剥离全部元信息头行后
 * 原样透传——宁可原文也不把头行泄给用户。缓存里仍保留原始 JSON 不动，
 * LLM 侧消费方（预检内联、get_cached_task_result）不受影响。
 * </p>
 */
public final class TaskResultFormatter {

    private static final Logger log = LoggerFactory.getLogger(TaskResultFormatter.class);

    /** 元信息头行（# task_id=… # registered_at=…），register/append 写入；append 多段时在正文中间 */
    private static final Pattern META_HEADER_LINE = Pattern.compile("(?m)^#\\s*task_id=.*$");

    /** 每个方向/分组最多列出的方案条数（飞猪按自身排序返回，保持源顺序截断） */
    private static final int MAX_TICKET_OPTIONS = 3;

    /** 酒店摘要最多列出的条数 */
    private static final int MAX_HOTELS = 5;

    private TaskResultFormatter() {
    }

    /**
     * 大交通摘要：trains[]/flights[]（两工具同构，字段名略有差异）渲染为分组列表；
     * append 多段（往返双查）按段分组，段标题取首段「出发站 → 到达站」。
     * 非车票/机票 JSON 的内容剥头行后透传。
     */
    public static String formatTrain(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        List<String> segments = splitSegments(raw);
        StringBuilder sb = new StringBuilder();
        boolean rendered = false;
        for (String seg : segments) {
            JSONObject json = tryParse(seg);
            JSONArray tickets = json == null ? null : firstArray(json, "trains", "flights");
            if (tickets == null || tickets.isEmpty()) {
                // 不认识的分段（12306 MCP 文本等）剥头行后原样保留——不静默丢数据
                sb.append(seg).append("\n\n");
                continue;
            }
            appendTicketGroup(sb, tickets);
            rendered = true;
        }
        if (!rendered) {
            log.debug("大交通缓存内容非车票/机票 JSON，剥头行透传（{} 字符）", raw.length());
            return stripMetaHeaders(raw);
        }
        return sb.toString().strip();
    }

    /** 住宿摘要：hotels[] 渲染为表格（前 {@value #MAX_HOTELS} 家）；非酒店 JSON 剥头行透传 */
    public static String formatHotel(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        JSONObject json = tryParse(stripMetaHeaders(raw));
        JSONArray hotels = json == null ? null : json.getJSONArray("hotels");
        if (hotels == null || hotels.isEmpty()) {
            return stripMetaHeaders(raw);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("| 酒店 | 类型 | 参考价 | 地址 | 来源 |\n");
        sb.append("| --- | --- | --- | --- | --- |\n");
        StringBuilder gallery = new StringBuilder();
        int shown = 0;
        for (Object item : hotels) {
            if (shown >= MAX_HOTELS) {
                break;
            }
            JSONObject h = (JSONObject) item;
            String name = nvl(h.getString("name"));
            String url = h.getString("detailUrl");
            String nameCell = url == null || url.isBlank() ? name : "[" + name + "](" + url + ")";
            sb.append("| ").append(nameCell)
                    .append(" | ").append(hotelType(h.getString("type")))
                    .append(" | ").append(nvl(h.getString("price"), "—"))
                    .append(" | ").append(nvl(h.getString("address"), "—"))
                    .append(" | ").append(nvl(h.getString("source"), "—"))
                    .append(" |\n");
            // v5-F2 图片透传：已展示酒店的图片收进图集（代码组装，随住宿节渲染）
            String img = h.getString("imageUrl");
            if (img != null && !img.isBlank()) {
                gallery.append("![").append(name.replace("[", "（").replace("]", "）"))
                        .append("](").append(img).append(") ");
            }
            shown++;
        }
        int total = json.getIntValue("count") > 0 ? json.getIntValue("count") : hotels.size();
        if (total > shown) {
            sb.append("\n共 ").append(total).append(" 家可选");
            JSONObject sources = json.getJSONObject("sources");
            if (sources != null && !sources.isEmpty()) {
                sb.append("（");
                List<String> parts = new ArrayList<>();
                for (String key : sources.keySet()) {
                    parts.add(key + " " + sources.get(key) + " 家");
                }
                sb.append(String.join("、", parts)).append("）");
            }
            sb.append("，以上按工具返回顺序取前 ").append(shown).append(" 家");
        }
        if (gallery.length() > 0) {
            sb.append("\n\n📷 图集：").append(gallery);
        }
        return sb.toString().strip();
    }

    /** 天气摘要：forecasts[] 渲染为表格 + 按全程最低温给一行穿衣建议；非天气 JSON 剥头行透传 */
    public static String formatWeather(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        JSONObject json = tryParse(stripMetaHeaders(raw));
        JSONArray forecasts = json == null ? null : json.getJSONArray("forecasts");
        if (forecasts == null || forecasts.isEmpty()) {
            return stripMetaHeaders(raw);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("| 日期 | 白天 | 夜间 | 气温 | 风 | 湿度 |\n");
        sb.append("| --- | --- | --- | --- | --- | --- |\n");
        Integer overallMin = null;
        int maxUv = -1;
        for (Object item : forecasts) {
            JSONObject f = (JSONObject) item;
            Integer tMin = parseInt(f.getString("temp_min"));
            Integer tMax = parseInt(f.getString("temp_max"));
            if (tMin != null && (overallMin == null || tMin < overallMin)) {
                overallMin = tMin;
            }
            Integer uv = parseInt(f.getString("uv_index"));
            if (uv != null && uv > maxUv) {
                maxUv = uv;
            }
            sb.append("| ").append(shortDate(f.getString("date")))
                    .append(" | ").append(nvl(f.getString("text_day"), "—"))
                    .append(" | ").append(nvl(f.getString("text_night"), "—"))
                    .append(" | ").append(tMin == null ? "?" : tMin).append("~")
                    .append(tMax == null ? "?" : tMax).append("℃")
                    .append(" | ").append(windText(f))
                    .append(" | ").append(nvl(f.getString("humidity"), "?")).append("%")
                    .append(" |\n");
        }
        String advice = clothingAdvice(overallMin, maxUv);
        if (advice != null) {
            sb.append("\n穿衣建议：").append(advice);
        }
        return sb.toString().strip();
    }

    /** 剥离全部元信息头行（register 的首行头 + append 多段的中间分隔头） */
    public static String stripMetaHeaders(String raw) {
        if (raw == null) {
            return null;
        }
        return META_HEADER_LINE.matcher(raw).replaceAll("").strip();
    }

    /** 按元信息头行切成内容段（append 多段形态）；无头行时整段返回 */
    private static List<String> splitSegments(String raw) {
        String cleaned = stripMetaHeaders(raw);
        List<String> segments = new ArrayList<>();
        for (String seg : cleaned.split("\\n\\s*\\n")) {
            if (!seg.isBlank()) {
                segments.add(seg.strip());
            }
        }
        if (segments.isEmpty()) {
            segments.add(cleaned);
        }
        return segments;
    }

    /** 渲染一组车票/机票方案（同一查询方向）；标题取首条的首段出发站→末段到达站 */
    private static void appendTicketGroup(StringBuilder sb, JSONArray tickets) {
        JSONObject first = tickets.getJSONObject(0);
        String title = directionTitle(first);
        if (title != null) {
            sb.append(title).append("\n");
        }
        int shown = 0;
        for (Object item : tickets) {
            if (shown >= MAX_TICKET_OPTIONS) {
                break;
            }
            sb.append("- ").append(ticketLine((JSONObject) item)).append("\n");
            shown++;
        }
        int omitted = tickets.size() - shown;
        if (omitted > 0) {
            sb.append("- 另有 ").append(omitted).append(" 个方案未列出\n");
        }
        sb.append("\n");
    }

    /** 单条方案行：价格 · 全程时长 · 直达/中转 + 各段明细 + 来源 + 预订链接 */
    private static String ticketLine(JSONObject t) {
        StringBuilder line = new StringBuilder();
        String price = t.getString("price");
        line.append(price == null || price.isBlank() ? "票价待确认" : trimPrice(price) + "元");
        String duration = durationText(t.getString("totalDuration"));
        if (duration != null) {
            line.append(" · 全程约").append(duration);
        }
        String journeyType = t.getString("journeyType");
        if (journeyType != null && !journeyType.isBlank()) {
            line.append(" · ").append(journeyType);
        }
        JSONArray legs = t.getJSONArray("segments");
        if (legs != null && !legs.isEmpty()) {
            List<String> legTexts = new ArrayList<>();
            for (Object item : legs) {
                legTexts.add(legText((JSONObject) item));
            }
            line.append("：").append(String.join("，中转 ", legTexts));
        }
        String source = t.getString("source");
        if (source != null && !source.isBlank()) {
            line.append("【").append(source).append("】");
        }
        String url = t.getString("jumpUrl");
        if (url != null && !url.isBlank()) {
            line.append("（[预订链接](").append(url).append(")）");
        }
        return line.toString();
    }

    /** 段明细：G1224 杭州东站→沈阳北站（10-22 08:29→18:46，二等座）；跨天到达标次日/第三日 */
    private static String legText(JSONObject leg) {
        String no = firstNonNull(leg, "trainNo", "flightNo");
        String dep = firstNonNull(leg, "depStation", "depAirport");
        String arr = firstNonNull(leg, "arrStation", "arrAirport");
        String seat = firstNonNull(leg, "seatClass", "cabinClass");
        StringBuilder sb = new StringBuilder();
        if (no != null) {
            sb.append(no).append(' ');
        }
        sb.append(nvl(dep, "?")).append("→").append(nvl(arr, "?"));
        String depTime = leg.getString("depTime");
        String arrTime = leg.getString("arrTime");
        if (depTime != null && arrTime != null) {
            sb.append("（").append(shortDateTime(depTime)).append("→")
                    .append(dayOffsetPrefix(depTime, arrTime)).append(timeOf(arrTime));
            if (seat != null && !seat.isBlank()) {
                sb.append("，").append(seat);
            }
            sb.append("）");
        } else if (seat != null && !seat.isBlank()) {
            sb.append("（").append(seat).append("）");
        }
        return sb.toString();
    }

    /** 分组标题：首段出发站 → 末段到达站（如「杭州东站 → 哈尔滨站」）；取不到返回 null */
    private static String directionTitle(JSONObject ticket) {
        JSONArray legs = ticket.getJSONArray("segments");
        if (legs == null || legs.isEmpty()) {
            return null;
        }
        String dep = firstNonNull(legs.getJSONObject(0), "depStation", "depAirport");
        String arr = firstNonNull(legs.getJSONObject(legs.size() - 1), "arrStation", "arrAirport");
        if (dep == null || arr == null) {
            return null;
        }
        return dep + " → " + arr;
    }

    /** 穿衣建议：按全程最低温分档；紫外线峰值 ≥5 追加防晒提示 */
    private static String clothingAdvice(Integer overallMin, int maxUv) {
        if (overallMin == null) {
            return null;
        }
        String base;
        if (overallMin <= 0) {
            base = "最低温 " + overallMin + "℃，羽绒服/厚棉衣必备，注意防寒保暖";
        } else if (overallMin <= 8) {
            base = "最低温 " + overallMin + "℃，需厚外套或薄羽绒，早晚注意添衣";
        } else if (overallMin <= 15) {
            base = "最低温 " + overallMin + "℃，外套/风衣即可，昼夜温差大时备薄衫";
        } else if (overallMin <= 22) {
            base = "最低温 " + overallMin + "℃，长袖加薄外套";
        } else {
            base = "最低温 " + overallMin + "℃，轻薄衣物即可";
        }
        return maxUv >= 5 ? base + "；白天紫外线较强，注意防晒" : base;
    }

    /** 酒店类型清洗：飞猪给「经济型/舒适型」保留；高德的 POI 分类串（含 ; 分层）无信息量置 — */
    private static String hotelType(String type) {
        if (type == null || type.isBlank() || type.contains(";") || type.contains("|")) {
            return "—";
        }
        return type;
    }

    private static String windText(JSONObject f) {
        String dir = f.getString("wind_dir_day");
        String scale = f.getString("wind_scale_day");
        if (dir == null && scale == null) {
            return "—";
        }
        return nvl(dir, "") + (scale == null ? "" : " " + scale + "级");
    }

    private static JSONObject tryParse(String text) {
        try {
            JSONObject json = JSON.parseObject(text);
            return json == null || json.isEmpty() ? null : json;
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONArray firstArray(JSONObject json, String... keys) {
        for (String key : keys) {
            JSONArray arr = json.getJSONArray(key);
            if (arr != null && !arr.isEmpty()) {
                return arr;
            }
        }
        return null;
    }

    private static String firstNonNull(JSONObject json, String... keys) {
        for (String key : keys) {
            String v = json.getString(key);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** 分钟数 → 「17小时37分」；非数字返回 null */
    private static String durationText(String minutes) {
        Long total = parseLong(minutes);
        if (total == null || total <= 0) {
            return null;
        }
        long h = total / 60;
        long m = total % 60;
        return h > 0 ? h + "小时" + (m > 0 ? m + "分" : "") : m + "分";
    }

    /** "910.00" → "910"；保留非整小数（"1049.50" → "1049.5"） */
    private static String trimPrice(String price) {
        try {
            double v = Double.parseDouble(price.trim());
            return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
        } catch (Exception e) {
            return price.trim();
        }
    }

    /** "2026-10-22 08:29:00" → "10-22 08:29"；无法解析原样返回 */
    private static String shortDateTime(String dateTime) {
        String t = dateTime.trim();
        try {
            String datePart = t.substring(0, 10);
            LocalDate d = LocalDate.parse(datePart);
            String time = timeOf(t);
            return String.format("%02d-%02d %s", d.getMonthValue(), d.getDayOfMonth(), time);
        } catch (Exception e) {
            return t;
        }
    }

    /** "2026-10-22" → "10-22"；无法解析原样返回 */
    private static String shortDate(String date) {
        try {
            LocalDate d = LocalDate.parse(date.trim());
            return String.format("%02d-%02d", d.getMonthValue(), d.getDayOfMonth());
        } catch (Exception e) {
            return nvl(date, "?");
        }
    }

    /** 取 HH:mm 部分；"2026-10-22 08:29:00" → "08:29" */
    private static String timeOf(String dateTime) {
        String t = dateTime.trim();
        int space = t.indexOf(' ');
        if (space < 0) {
            return t;
        }
        String time = t.substring(space + 1);
        return time.length() >= 5 ? time.substring(0, 5) : time;
    }

    /** 跨天到达前缀：同日 ""，+1 天 "次日"，+2 天 "第三日"，更多 "（+N 日）" */
    private static String dayOffsetPrefix(String depTime, String arrTime) {
        try {
            LocalDate dep = LocalDate.parse(depTime.trim().substring(0, 10));
            LocalDate arr = LocalDate.parse(arrTime.trim().substring(0, 10));
            long diff = arr.toEpochDay() - dep.toEpochDay();
            if (diff <= 0) {
                return "";
            }
            if (diff == 1) {
                return "次日";
            }
            if (diff == 2) {
                return "第三日";
            }
            return "+" + diff + "日 ";
        } catch (Exception e) {
            return "";
        }
    }

    private static Integer parseInt(String s) {
        Long v = parseLong(s);
        return v == null ? null : v.intValue();
    }

    private static Long parseLong(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String nvl(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
