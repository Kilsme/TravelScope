package com.travelscope.service.review;

import java.util.List;
import java.util.Locale;

/** 稳定原因码到 reviewer 唯一允许动作的确定性路由。 */
public final class ReviewActionRouter {
    public Route route(String reasonCode) {
        String code = reasonCode == null ? "" : reasonCode.toUpperCase(Locale.ROOT);
        return switch (code) {
            case "DRAFT_MISSING" -> new Route(Status.NOT_READY, List.of(), false, false);
            case "DUPLICATE_POI", "PREFERENCE_MISSING", "PREFERENCE_COVERAGE" ->
                    revise("revise_poi_selection");
            case "OPENING_HOUR_CONFLICT", "LATE_OUTDOOR_ACTIVITY", "DAILY_BALANCE",
                    "TIMELINE_OVERLAP", "TRANSPORT_TIMELINE_CONFLICT", "QUALITY_BELOW_THRESHOLD" -> revise("plan_route");
            case "HOTEL_BUDGET_MISMATCH" -> revise("searchHotels", "assemble_itinerary");
            case "BUDGET_ARITHMETIC_MISMATCH" ->
                    new Route(Status.CODE_RECALCULATE, List.of("recalculate_budget"), false, true);
            default -> new Route(Status.UNROUTABLE, List.of(), false, false);
        };
    }

    private static Route revise(String... actions) {
        return new Route(Status.REVISE_ONCE, List.of(actions), true, true);
    }

    public enum Status { REVISE_ONCE, CODE_RECALCULATE, NOT_READY, UNROUTABLE }
    public record Route(Status status, List<String> allowedActions, boolean llmAllowed,
                        boolean mustRecheck) {}
}
