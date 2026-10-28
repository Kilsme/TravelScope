package com.travelscope.service.route.quality;

import com.travelscope.collab.RoutePlan;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 现有 RoutePlan 的配置化质量评分纯函数；不触碰路线求解输出。 */
public final class RouteQualityScorer {
    public Result score(RoutePlan plan, Context context) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(context, "context");
        List<Penalty> penalties = new ArrayList<>();
        scoreCommute(plan, context, penalties);
        scoreBalance(plan, penalties);
        scoreOpening(plan, context, penalties);
        scoreLate(plan, context, penalties);
        scoreMealGap(plan, penalties);
        scorePreference(plan, context, penalties);
        scoreDiversity(plan, context, penalties);
        scoreTransport(plan, penalties);
        scoreHotel(plan, penalties);

        EnumMap<Dimension, Integer> scores = new EnumMap<>(Dimension.class);
        for (Dimension d : Dimension.values()) scores.put(d, 100);
        for (Penalty p : penalties) scores.compute(p.dimension(), (k, v) -> Math.max(0, v - p.points()));
        double weighted = 0;
        int weightSum = 0;
        for (Dimension d : Dimension.values()) {
            int weight = context.weights().values().getOrDefault(d, 0);
            weighted += scores.get(d) * weight;
            weightSum += weight;
        }
        return new Result(Map.copyOf(scores), weightSum == 0 ? 0 : weighted / weightSum,
                List.copyOf(penalties));
    }

    private static void scoreCommute(RoutePlan plan, Context c, List<Penalty> out) {
        for (var day : plan.days()) if (day.commuteMinutesTotal() > c.maxDailyCommuteMinutes())
            add(out, Dimension.COMMUTE, day, day.nodes().isEmpty() ? "DAY" : day.nodes().getLast().name(),
                    "DAILY_COMMUTE_EXCESS", Math.min(60, (day.commuteMinutesTotal() - c.maxDailyCommuteMinutes()) / 2),
                    "当日通勤 " + day.commuteMinutesTotal() + " 分钟超过阈值");
    }

    private static void scoreBalance(RoutePlan plan, List<Penalty> out) {
        if (plan.days().size() < 2) return;
        double avg = plan.days().stream().mapToLong(RouteQualityScorer::activityMinutes).average().orElse(0);
        for (var day : plan.days()) {
            long minutes = activityMinutes(day);
            if (avg > 0 && Math.abs(minutes - avg) / avg > .30)
                add(out, Dimension.DAILY_BALANCE, day, day.nodes().isEmpty() ? "DAY" : day.nodes().getLast().name(),
                        "DAILY_LOAD_IMBALANCE", Math.min(50, (int) (Math.abs(minutes - avg) / avg * 50)),
                        "当日活动 " + minutes + " 分钟，均值 " + Math.round(avg) + " 分钟");
        }
    }

    private static void scoreOpening(RoutePlan plan, Context c, List<Penalty> out) {
        for (var day : plan.days()) for (var n : day.nodes()) {
            TimeWindow w = c.openingWindows().get(n.name());
            if (w != null && (time(n.startTime()).isBefore(w.open()) || time(n.endTime()).isAfter(w.close())))
                add(out, Dimension.OPENING_FIT, day, n.name(), "OUTSIDE_OPENING_WINDOW", 60,
                        "排程 " + n.startTime() + "-" + n.endTime() + " 超出开放时间");
        }
    }

    private static void scoreLate(RoutePlan plan, Context c, List<Penalty> out) {
        for (var day : plan.days()) for (var n : day.nodes()) if (c.outdoorNodes().contains(n.name())
                && time(n.endTime()).isAfter(c.latestOutdoorEnd()))
            add(out, Dimension.LATE_ACTIVITY, day, n.name(), "LATE_OUTDOOR_ACTIVITY", 50,
                    "户外活动结束于 " + n.endTime());
    }

    private static void scoreMealGap(RoutePlan plan, List<Penalty> out) {
        for (var day : plan.days()) {
            boolean spansMeal = day.nodes().stream().anyMatch(n -> time(n.startTime()).isBefore(LocalTime.NOON)
                    && time(n.endTime()).isAfter(LocalTime.of(14, 0)));
            boolean meal = day.nodes().stream().anyMatch(n -> n.type() == RoutePlan.NodeType.MEAL);
            if (spansMeal && !meal) add(out, Dimension.MEAL_GAP, day, "DAY", "MEAL_WINDOW_MISSING", 30,
                    "路线跨越午餐窗口但没有 MEAL 节点");
        }
    }

    private static void scorePreference(RoutePlan plan, Context c, List<Penalty> out) {
        for (String keyword : c.preferenceKeywords()) {
            boolean hit = plan.days().stream().flatMap(d -> d.nodes().stream()).anyMatch(n -> n.name().contains(keyword));
            if (!hit && !plan.days().isEmpty()) add(out, Dimension.PREFERENCE, plan.days().getFirst(), "PLAN",
                    "PREFERENCE_NOT_COVERED", 25, "未覆盖偏好：" + keyword);
        }
    }

    private static void scoreDiversity(RoutePlan plan, Context c, List<Penalty> out) {
        for (var day : plan.days()) {
            Set<String> categories = new HashSet<>();
            day.nodes().forEach(n -> categories.add(c.nodeCategories().getOrDefault(n.name(), n.type().name())));
            long poiCount = day.nodes().stream().filter(n -> n.type() == RoutePlan.NodeType.POI).count();
            if (poiCount >= 3 && categories.size() == 1) add(out, Dimension.DIVERSITY, day, day.nodes().getFirst().name(),
                    "LOW_DAY_DIVERSITY", 25, "当日节点类型单一");
        }
    }

    private static void scoreTransport(RoutePlan plan, List<Penalty> out) {
        for (var day : plan.days()) for (var n : day.nodes()) if (n.type() == RoutePlan.NodeType.INTERCITY_TRANSPORT
                && (!n.fixed() || n.startTime() == null))
            add(out, Dimension.TRANSPORT_FIT, day, n.name(), "TRANSPORT_NOT_FIXED", 50, "城际交通未形成固定边界");
    }

    private static void scoreHotel(RoutePlan plan, List<Penalty> out) {
        for (var day : plan.days()) if (!day.nodes().isEmpty()) {
            var first = day.nodes().getFirst(); var last = day.nodes().getLast();
            boolean anchored = first.type() == RoutePlan.NodeType.HOTEL_CHECKIN
                    && last.type() == RoutePlan.NodeType.HOTEL_CHECKOUT;
            if (!anchored) add(out, Dimension.HOTEL_FIT, day, first.name(), "HOTEL_DAILY_ANCHOR_MISSING", 35,
                    "每日路线未同时从酒店出发并返回酒店");
        }
    }

    private static long activityMinutes(RoutePlan.RouteDay day) {
        return day.nodes().stream().filter(n -> n.type() == RoutePlan.NodeType.POI || n.type() == RoutePlan.NodeType.MEAL)
                .mapToLong(n -> Math.max(0, Duration.between(time(n.startTime()), time(n.endTime())).toMinutes())).sum();
    }
    private static LocalTime time(String value) { return LocalTime.parse(value); }
    private static void add(List<Penalty> out, Dimension d, RoutePlan.RouteDay day, String node,
                            String code, int points, String reason) {
        out.add(new Penalty(d, day.day(), node, code, Math.max(1, points), reason));
    }

    public enum Dimension { COMMUTE, DAILY_BALANCE, OPENING_FIT, LATE_ACTIVITY, MEAL_GAP,
        PREFERENCE, DIVERSITY, TRANSPORT_FIT, HOTEL_FIT }
    public record Penalty(Dimension dimension, int day, String node, String reasonCode, int points, String reason) {}
    public record Result(Map<Dimension, Integer> scores, double totalScore, List<Penalty> penalties) {
        public int commuteScore() { return scores.get(Dimension.COMMUTE); }
        public int dailyBalanceScore() { return scores.get(Dimension.DAILY_BALANCE); }
        public int openingFitScore() { return scores.get(Dimension.OPENING_FIT); }
        public int lateActivityScore() { return scores.get(Dimension.LATE_ACTIVITY); }
        public int mealGapScore() { return scores.get(Dimension.MEAL_GAP); }
        public int preferenceScore() { return scores.get(Dimension.PREFERENCE); }
        public int diversityScore() { return scores.get(Dimension.DIVERSITY); }
        public int transportFitScore() { return scores.get(Dimension.TRANSPORT_FIT); }
        public int hotelFitScore() { return scores.get(Dimension.HOTEL_FIT); }
    }
    public record TimeWindow(LocalTime open, LocalTime close) {}

    public record Weights(Map<Dimension, Integer> values) {
        public Weights { values = Map.copyOf(values); }
        public static Weights defaults() {
            EnumMap<Dimension, Integer> m = new EnumMap<>(Dimension.class);
            for (Dimension d : Dimension.values()) m.put(d, 1);
            return new Weights(m);
        }
        public Weights with(Map<Dimension, Integer> overrides) {
            EnumMap<Dimension, Integer> m = new EnumMap<>(values); m.putAll(overrides); return new Weights(m);
        }
    }

    public record Context(Weights weights, int maxDailyCommuteMinutes, LocalTime latestOutdoorEnd,
                          Set<String> outdoorNodes, Map<String, TimeWindow> openingWindows,
                          Set<String> preferenceKeywords, Map<String, String> nodeCategories) {
        public Context { outdoorNodes = Set.copyOf(outdoorNodes); openingWindows = Map.copyOf(openingWindows);
            preferenceKeywords = Set.copyOf(preferenceKeywords); nodeCategories = Map.copyOf(nodeCategories); }
        public static Context defaults() { return new Context(Weights.defaults(), 120, LocalTime.of(19, 0),
                Set.of(), Map.of(), Set.of(), Map.of()); }
        public Context withOutdoorNodes(Set<String> v) { return new Context(weights, maxDailyCommuteMinutes, latestOutdoorEnd, v, openingWindows, preferenceKeywords, nodeCategories); }
        public Context withWeights(Weights v) { return new Context(v, maxDailyCommuteMinutes, latestOutdoorEnd, outdoorNodes, openingWindows, preferenceKeywords, nodeCategories); }
        public Context withOpeningWindows(Map<String, TimeWindow> v) { return new Context(weights, maxDailyCommuteMinutes, latestOutdoorEnd, outdoorNodes, v, preferenceKeywords, nodeCategories); }
        public Context withPreferenceKeywords(Set<String> v) { return new Context(weights, maxDailyCommuteMinutes, latestOutdoorEnd, outdoorNodes, openingWindows, v, nodeCategories); }
        public Context withNodeCategories(Map<String, String> v) { return new Context(weights, maxDailyCommuteMinutes, latestOutdoorEnd, outdoorNodes, openingWindows, preferenceKeywords, v); }
        public Context withMaxDailyCommuteMinutes(int v) { return new Context(weights, v, latestOutdoorEnd, outdoorNodes, openingWindows, preferenceKeywords, nodeCategories); }
        public Context withLatestOutdoorEnd(LocalTime v) { return new Context(weights, maxDailyCommuteMinutes, v, outdoorNodes, openingWindows, preferenceKeywords, nodeCategories); }
    }
}
