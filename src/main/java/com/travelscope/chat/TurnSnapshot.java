package com.travelscope.chat;

import com.alibaba.fastjson2.JSON;

import java.util.*;

/** Only committed public events are folded into this recoverable UI state. */
public final class TurnSnapshot {
    private TurnSnapshot() {}

    public static Map<String, Object> empty() {
        var s = new LinkedHashMap<String, Object>();
        s.put("content", "");
        s.put("activities", new ArrayList<>());
        s.put("itineraryDays", new ArrayList<>());
        return s;
    }

    @SuppressWarnings("unchecked")
    public static void apply(Map<String, Object> s, String type, Object payload) {
        switch (type) {
            case "delta" ->
                    s.put(
                            "content",
                            Objects.toString(s.get("content"), "") + Objects.toString(payload, ""));
            case "clarify_question" ->
                    s.put("content", Objects.toString(s.get("content"), "") + "\n\n💬 " + payload);
            case "agent_status" ->
                    ((List<Object>) s.computeIfAbsent("activities", k -> new ArrayList<>()))
                            .add(payload);
            case "intent" ->
                    s.put("intent", payload instanceof Map<?, ?> m ? m.get("intent") : "UNKNOWN");
            case "requirement_form" -> {
                s.put("requirementForm", payload);
                s.putIfAbsent("formSubmitted", false);
            }
            case "review_report" -> s.put("reviewReport", JSON.toJSONString(payload));
            case "itinerary_skeleton" -> s.put("itinerarySkeleton", payload);
            case "itinerary_day" -> {
                var day = (Map<String, Object>) payload;
                var days =
                        (List<Map<String, Object>>)
                                s.computeIfAbsent("itineraryDays", k -> new ArrayList<>());
                int n = ((Number) day.get("day")).intValue();
                var old =
                        days.stream()
                                .filter(d -> ((Number) d.get("day")).intValue() == n)
                                .findFirst();
                if (old.isEmpty()
                        || ((Number) old.get().get("version")).intValue()
                                < ((Number) day.get("version")).intValue()) {
                    old.ifPresent(days::remove);
                    days.add(day);
                    days.sort(Comparator.comparingInt(d -> ((Number) d.get("day")).intValue()));
                }
            }
            case "terminal" -> {
                var t = (Map<String, Object>) payload;
                s.put("content", t.get("content"));
                s.put("terminal", payload);
            }
            default -> {}
        }
    }

    public static Object publicPayload(String type, String payload) {
        if (Set.of(
                        "intent",
                        "agent_status",
                        "requirement_form",
                        "review_report",
                        "itinerary_day",
                        "itinerary_skeleton")
                .contains(type)) {
            var value = JSON.parse(payload);
            if (value instanceof Map<?, ?> map)
                map.remove("seq"); // transport seq belongs to the journal
            return value;
        }
        return payload == null ? "" : payload;
    }
}
