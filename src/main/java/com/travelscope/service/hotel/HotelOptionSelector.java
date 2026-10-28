package com.travelscope.service.hotel;

import com.travelscope.collab.BudgetConstraint;
import com.travelscope.collab.HotelSelection;
import com.travelscope.collab.HotelSelection.HotelOption;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 以预算、连续有房、路线中心、车站、地铁和取消政策确定性选择酒店。 */
public class HotelOptionSelector {
    public HotelSelection select(List<HotelOption> options, BudgetConstraint budget,
                                 double routeCenterLng, double routeCenterLat,
                                 double stationLng, double stationLat, int nights) {
        Objects.requireNonNull(budget, "budget");
        List<Scored> scored = options.stream().filter(HotelOption::continuousAvailability)
                .filter(h -> h.nights() == 0 || h.nights() >= nights)
                .map(h -> new Scored(h, score(h, budget, routeCenterLng, routeCenterLat,
                        stationLng, stationLat)))
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparing(s -> s.hotel().name())).toList();
        if (scored.isEmpty()) throw new IllegalArgumentException("无连续有房的酒店方案");
        HotelOption selected = scored.getFirst().hotel();
        List<String> explanation = List.of(
                "预算匹配：每晚 " + selected.nightlyPrice() + " " + budget.currency(),
                "连续有房：" + selected.checkIn() + " 至 " + selected.checkOut(),
                "路线中心距离：" + Math.round(distanceMeters(selected.lng(), selected.lat(), routeCenterLng, routeCenterLat)) + "m",
                "车站距离：" + Math.round(distanceMeters(selected.lng(), selected.lat(), stationLng, stationLat)) + "m",
                "地铁可达：" + selected.metroAccessible(), "可取消：" + selected.refundable());
        return new HotelSelection(selected, scored.stream().skip(1).limit(2).map(Scored::hotel).toList(), explanation);
    }

    private double score(HotelOption h, BudgetConstraint b, double centerLng, double centerLat,
                         double stationLng, double stationLat) {
        double budgetScore = budgetScore(h, b);
        double center = Math.max(0, 1 - distanceMeters(h.lng(), h.lat(), centerLng, centerLat) / 15000.0);
        double station = Math.max(0, 1 - distanceMeters(h.lng(), h.lat(), stationLng, stationLat) / 20000.0);
        double quality = Math.min(1, h.starLevel() / 5.0);
        return budgetScore * 3.0 + center * 2.5 + station + (h.metroAccessible() ? 0.8 : 0)
                + (h.refundable() ? 0.7 : 0) + qualityWeight(b, quality);
    }

    private double budgetScore(HotelOption h, BudgetConstraint b) {
        BigDecimal price = h.nightlyPrice();
        BigDecimal min = b.min();
        BigDecimal max = b.max();
        if (max != null && price.compareTo(max) > 0) return Math.max(-2, -price.subtract(max).doubleValue() / max.doubleValue());
        if (min != null && price.compareTo(min) < 0) {
            return b.travelStyle() == BudgetConstraint.TravelStyle.LUXURY ? -1.5 : 0.6;
        }
        if (max == null) return 0.7;
        double target = b.travelStyle() == BudgetConstraint.TravelStyle.LUXURY ? 0.85
                : b.travelStyle() == BudgetConstraint.TravelStyle.BUDGET ? 0.45 : 0.65;
        double ratio = price.doubleValue() / max.doubleValue();
        return Math.max(0, 1 - Math.abs(ratio - target));
    }

    private double qualityWeight(BudgetConstraint b, double quality) {
        return switch (b.travelStyle()) {
            case LUXURY -> quality * 2.5;
            case COMFORT -> quality;
            case BUDGET -> quality * 0.2;
        };
    }

    private static double distanceMeters(double lng1, double lat1, double lng2, double lat2) {
        double dLat = Math.toRadians(lat2 - lat1), dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private record Scored(HotelOption hotel, double score) {}
}
