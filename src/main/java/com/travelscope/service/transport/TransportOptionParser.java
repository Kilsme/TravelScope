package com.travelscope.service.transport;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.travelscope.collab.TransportSelection.TransportOption;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 将既有飞猪 trains[]/flights[] 实时结果适配为统一候选。 */
public class TransportOptionParser {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public List<TransportOption> parse(String jsonText) {
        JSONObject root = JSON.parseObject(jsonText);
        JSONArray items = root.getJSONArray("trains");
        String mode = "TRAIN";
        if (items == null) {
            items = root.getJSONArray("flights");
            mode = "FLIGHT";
        }
        if (items == null) return List.of();
        List<TransportOption> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            JSONArray segments = item.getJSONArray("segments");
            if (segments == null || segments.isEmpty()) continue;
            JSONObject first = segments.getJSONObject(0);
            JSONObject last = segments.getJSONObject(segments.size() - 1);
            LocalDateTime departure = LocalDateTime.parse(first.getString("depTime"), TIME);
            LocalDateTime arrival = LocalDateTime.parse(last.getString("arrTime"), TIME);
            String noKey = "TRAIN".equals(mode) ? "trainNo" : "flightNo";
            String serviceNo = segments.stream().map(v -> ((JSONObject) v).getString(noKey))
                    .reduce((a, b) -> a + "/" + b).orElse("UNKNOWN");
            long duration = item.getLongValue("totalDuration");
            if (duration <= 0) duration = Duration.between(departure, arrival).toMinutes();
            int transfers = Math.max(0, segments.size() - 1);
            result.add(new TransportOption(serviceNo, mode, parsePrice(item.getString("price")),
                    departure, arrival, duration, transfers, transfers == 0,
                    item.getString("source") == null ? root.getString("source") : item.getString("source"),
                    item.getString("jumpUrl")));
        }
        return List.copyOf(result);
    }

    private static BigDecimal parsePrice(String value) {
        if (value == null) return BigDecimal.ZERO;
        String numeric = value.replaceAll("[^0-9.]", "");
        return numeric.isBlank() ? BigDecimal.ZERO : new BigDecimal(numeric);
    }
}
