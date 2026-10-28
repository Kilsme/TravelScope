package com.travelscope.service.review;

import com.travelscope.collab.ItineraryDraft;
import com.travelscope.collab.PoiShortlist;
import com.travelscope.collab.RoutePlan;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reviewer Gate 的独立纯规则集合；每个方法只检查一种稳定 reasonCode。 */
public final class ReviewGateRules {
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d{1,2})?");
    private ReviewGateRules() {}

    public record Failure(String reasonCode, String detail) {}

    public static List<Failure> duplicatePoi(ItineraryDraft draft) {
        List<Failure> failures = new ArrayList<>();
        for (var day : draft.days()) {
            Set<String> seen = new HashSet<>();
            for (var item : day.items()) {
                String key = item.location().replaceAll("[\\s·•・_\\-—]", "").toLowerCase();
                if (!seen.add(key)) failures.add(new Failure("DUPLICATE_POI",
                        "Day" + day.day() + " 重复 POI: " + item.location()));
            }
        }
        return failures;
    }

    public static List<Failure> openingHourConflict(RoutePlan plan, PoiShortlist shortlist) {
        if (plan == null || shortlist == null) return List.of();
        java.util.Map<String, String> hours = new java.util.HashMap<>();
        shortlist.pois().forEach(p -> hours.put(p.name(), p.openHours()));
        List<Failure> out = new ArrayList<>();
        for (var day : plan.days()) for (var node : day.nodes()) {
            String raw = hours.get(node.name());
            if (raw == null) continue;
            Matcher m = Pattern.compile("(\\d{1,2}:\\d{2})-(\\d{1,2}:\\d{2})").matcher(raw);
            if (m.find() && (LocalTime.parse(node.startTime()).isBefore(LocalTime.parse(normalize(m.group(1))))
                    || LocalTime.parse(node.endTime()).isAfter(LocalTime.parse(normalize(m.group(2))))))
                out.add(new Failure("OPENING_HOUR_CONFLICT", "Day" + day.day() + " " + node.name()
                        + " 排程 " + node.startTime() + "-" + node.endTime() + " 超出 " + raw));
        }
        return out;
    }

    public static List<Failure> lateOutdoorActivity(RoutePlan plan) {
        if (plan == null) return List.of();
        List<Failure> out = new ArrayList<>();
        for (var day : plan.days()) for (var node : day.nodes()) {
            String text = node.name() + " " + (node.scheduleNote() == null ? "" : node.scheduleNote());
            boolean outdoor = text.matches(".*(山|湖|溪|公园|湿地|古道|户外|徒步|景区).*?");
            if (outdoor && LocalTime.parse(node.endTime()).isAfter(LocalTime.of(19, 0)))
                out.add(new Failure("LATE_OUTDOOR_ACTIVITY", "Day" + day.day() + " " + node.name()
                        + " 户外活动结束过晚: " + node.endTime()));
        }
        return out;
    }

    public static List<Failure> transportTimelineConflict(RoutePlan plan) {
        if (plan == null) return List.of();
        List<Failure> out = new ArrayList<>();
        for (var day : plan.days()) {
            for (int i = 0; i < day.nodes().size(); i++) {
                var n = day.nodes().get(i);
                if (n.type() != RoutePlan.NodeType.INTERCITY_TRANSPORT) continue;
                if (i > 0 && LocalTime.parse(day.nodes().get(i - 1).endTime()).isAfter(LocalTime.parse(n.startTime())))
                    out.add(new Failure("TRANSPORT_TIMELINE_CONFLICT", "Day" + day.day() + " 城际交通前活动侵占发车时间: " + n.name()));
                if (i + 1 < day.nodes().size() && LocalTime.parse(day.nodes().get(i + 1).startTime()).isBefore(LocalTime.parse(n.endTime())))
                    out.add(new Failure("TRANSPORT_TIMELINE_CONFLICT", "Day" + day.day() + " 城际交通后活动早于到达: " + n.name()));
            }
        }
        return out;
    }

    public static List<Failure> budgetArithmetic(ItineraryDraft draft) {
        if (draft.budget() == null || draft.budget().breakdown().isEmpty()) return List.of();
        BigDecimal sum = BigDecimal.ZERO;
        for (var item : draft.budget().breakdown()) {
            BigDecimal value = number(item.amount());
            // 非结构化旧预算继续由既有软规则处理，Gate 只核验全部可解析的确定性金额。
            if (value == null || item.amount().matches(".*[~～约起].*")) return List.of();
            sum = sum.add(value);
        }
        String totalText = draft.budget().totalEstimate();
        BigDecimal total = number(totalText);
        if (totalText == null || totalText.matches(".*[~～约起].*")) return List.of();
        return total != null && sum.compareTo(total) != 0
                ? List.of(new Failure("BUDGET_ARITHMETIC_MISMATCH", "分项合计 " + sum + "，总额 " + total)) : List.of();
    }

    public static List<Failure> unexplainedUnplaced(RoutePlan plan, ItineraryDraft draft) {
        if (plan == null) return List.of();
        String text = String.join(" ", draft.notes()) + " " + String.join(" ", draft.pendingConfirm());
        return plan.solverMeta().unplaced().stream()
                // 旧草案可能只有取舍描述；只要名称或稳定原因码出现即视为已解释。
                .filter(p -> !text.contains(p.name()) && !text.contains(p.reason().name()))
                .map(p -> new Failure("UNEXPLAINED_UNPLACED", p.name() + " 未排入但草案未解释 " + p.reason()))
                .toList();
    }

    public static List<Failure> staleDraft(Path taskDir) {
        Path draft = taskDir.resolve("itinerary_draft.json");
        if (!Files.exists(draft)) return List.of();
        long draftTime = draft.toFile().lastModified();
        for (String upstream : List.of("route_plan.json", "poi_shortlist.json", "transport_selection.json", "hotel_selection.json")) {
            Path file = taskDir.resolve(upstream);
            // 文件系统写入存在毫秒级抖动；至少相差 1 秒才判定为真实上游更新。
            if (Files.exists(file) && file.toFile().lastModified() - draftTime >= 1_000)
                return List.of(new Failure("REVIEW_STALE_DRAFT", upstream + " 晚于 itinerary_draft.json"));
        }
        return List.of();
    }

    private static BigDecimal number(String text) { if (text == null) return null; Matcher m = NUMBER.matcher(text); return m.find() ? new BigDecimal(m.group()) : null; }
    private static String normalize(String t) { return t.length() == 4 ? "0" + t : t; }
}
