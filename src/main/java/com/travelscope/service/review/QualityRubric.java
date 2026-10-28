package com.travelscope.service.review;

import com.travelscope.collab.IntakeResult;
import com.travelscope.collab.ItineraryDraft;
import com.travelscope.collab.PoiShortlist;
import com.travelscope.collab.RoutePlan;
import com.travelscope.service.route.quality.RouteQualityScorer;

import java.time.LocalTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Gate 通过后的确定性八维交付质量评分。 */
public final class QualityRubric {
    public static final int DEFAULT_THRESHOLD = 80;

    public Result score(ItineraryDraft draft, RoutePlan plan, PoiShortlist shortlist, IntakeResult intake) {
        EnumMap<Dimension, Integer> scores = new EnumMap<>(Dimension.class);
        RouteQualityScorer.Result route = routeScore(plan, shortlist, intake);
        scores.put(Dimension.TRANSPORT_FEASIBILITY, route == null ? 60
                : average(route.commuteScore(), route.transportFitScore(), route.hotelFitScore()));
        scores.put(Dimension.OPENING_HOUR_FIT, route == null ? 60 : route.openingFitScore());
        scores.put(Dimension.DAILY_BALANCE, route == null ? 60 : route.dailyBalanceScore());
        scores.put(Dimension.POI_DIVERSITY, route == null ? 60 : route.diversityScore());
        scores.put(Dimension.PREFERENCE_FIT, route == null ? preferenceFromDraft(draft, intake) : route.preferenceScore());
        scores.put(Dimension.BUDGET_FIT, budgetFit(draft));
        scores.put(Dimension.INFORMATION_COMPLETENESS, completeness(draft, plan, shortlist));
        scores.put(Dimension.ACTIONABILITY, actionability(draft));
        int total = (int) Math.round(scores.values().stream().mapToInt(Integer::intValue).average().orElse(0));
        List<Suggestion> suggestions = scores.entrySet().stream().filter(e -> e.getValue() < 80)
                .map(e -> new Suggestion(e.getKey(), suggestion(e.getKey()))).toList();
        return new Result(Map.copyOf(scores), total, DEFAULT_THRESHOLD, suggestions);
    }

    private static RouteQualityScorer.Result routeScore(RoutePlan plan, PoiShortlist shortlist, IntakeResult intake) {
        if (plan == null) return null;
        Set<String> outdoors = new HashSet<>(); Map<String, RouteQualityScorer.TimeWindow> windows = new HashMap<>();
        Map<String, String> categories = new HashMap<>();
        if (shortlist != null) shortlist.pois().forEach(p -> {
            String text = p.name() + " " + (p.reason() == null ? "" : p.reason());
            if (text.matches(".*(山|湖|溪|公园|湿地|古道|徒步|户外).*")) outdoors.add(p.name());
            categories.put(p.name(), p.reason() == null ? "POI" : p.reason());
            if (p.openHours() != null) {
                var m = java.util.regex.Pattern.compile("(\\d{1,2}:\\d{2})-(\\d{1,2}:\\d{2})").matcher(p.openHours());
                if (m.find()) windows.put(p.name(), new RouteQualityScorer.TimeWindow(time(m.group(1)), time(m.group(2))));
            }
        });
        Set<String> prefs = new HashSet<>();
        if (intake != null && intake.fields().preference() != null && intake.fields().preference().value() != null)
            prefs.addAll(List.of(intake.fields().preference().value().split("[,，、\\s]+")));
        var context = RouteQualityScorer.Context.defaults().withOutdoorNodes(outdoors)
                .withOpeningWindows(windows).withNodeCategories(categories).withPreferenceKeywords(prefs);
        return new RouteQualityScorer().score(plan, context);
    }

    private static int budgetFit(ItineraryDraft d) {
        if (d.budget() == null) return 40;
        int score = 60;
        if (d.budget().totalEstimate() != null && d.budget().totalEstimate().matches(".*\\d.*")) score += 20;
        if (!d.budget().breakdown().isEmpty()) score += 10;
        if (d.budget().breakdown().stream().allMatch(i -> i.note() != null && !i.note().isBlank())) score += 10;
        return score;
    }
    private static int completeness(ItineraryDraft d, RoutePlan p, PoiShortlist s) {
        int score = 40;
        if (!d.days().isEmpty()) score += 20;
        if (d.budget() != null) score += 15;
        if (p != null) score += 15;
        if (s != null && !s.pois().isEmpty()) score += 10;
        return score;
    }
    private static int actionability(ItineraryDraft d) {
        long items = d.days().stream().flatMap(x -> x.items().stream()).count();
        if (items == 0) return 30;
        long actionable = d.days().stream().flatMap(x -> x.items().stream())
                .filter(i -> i.time().matches("\\d{2}:\\d{2}-\\d{2}:\\d{2}") && !i.location().isBlank()).count();
        return (int) Math.round(100.0 * actionable / items);
    }
    private static int preferenceFromDraft(ItineraryDraft d, IntakeResult i) { return i == null ? 80 : 70; }
    private static int average(int... values) { return java.util.Arrays.stream(values).sum() / values.length; }
    private static LocalTime time(String value) { return LocalTime.parse(value.length() == 4 ? "0" + value : value); }
    private static String suggestion(Dimension d) { return switch (d) {
        case TRANSPORT_FEASIBILITY -> "减少单日通勤并补齐固定交通/酒店锚点";
        case OPENING_HOUR_FIT -> "按开放时间重新执行 plan_route";
        case DAILY_BALANCE -> "均衡各日有效游览时长";
        case POI_DIVERSITY -> "同日补充不同类型 POI";
        case PREFERENCE_FIT -> "用 revise_poi_selection 补齐未覆盖偏好";
        case BUDGET_FIT -> "补齐结构化预算分项和价格来源";
        case INFORMATION_COMPLETENESS -> "补齐 route/shortlist/budget 契约字段";
        case ACTIONABILITY -> "补齐每项明确时间和地点";
    }; }

    public enum Dimension { TRANSPORT_FEASIBILITY, OPENING_HOUR_FIT, DAILY_BALANCE, POI_DIVERSITY,
        PREFERENCE_FIT, BUDGET_FIT, INFORMATION_COMPLETENESS, ACTIONABILITY }
    public record Suggestion(Dimension dimension, String action) {}
    public record Result(Map<Dimension, Integer> scores, int total, int threshold, List<Suggestion> suggestions) {
        public boolean deliverable() { return total >= threshold; }
    }
}
