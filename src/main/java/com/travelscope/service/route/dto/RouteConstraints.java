package com.travelscope.service.route.dto;

/**
 * 路线求解约束（VRPTW 语义映射：车辆=天、车辆起点=酒店、时间窗=开放时间、
 * slack=transferBufferMin 转场缓冲、容量=maxDailyCommuteMin 单日通勤上限）。
 * <p>
 * 默认值经 {@link #ofDays(int)} / {@link #withHotel} 承载；紧凑构造器只做不变式校验。
 * </p>
 */
public record RouteConstraints(
        int days,
        String dailyStart,
        String dailyEnd,
        String lunchStart,
        String lunchEnd,
        int maxDailyCommuteMin,
        int transferBufferMin,
        String hotelName,
        Double hotelLng,
        Double hotelLat,
        boolean preferTransit,
        String pace,
        String walkingTolerance) {

    /** 旧调用兼容；节奏字段只透传，不参与本 Slice 求解评分。 */
    public RouteConstraints(int days, String dailyStart, String dailyEnd,
                            String lunchStart, String lunchEnd, int maxDailyCommuteMin,
                            int transferBufferMin, String hotelName, Double hotelLng,
                            Double hotelLat, boolean preferTransit) {
        this(days, dailyStart, dailyEnd, lunchStart, lunchEnd, maxDailyCommuteMin,
                transferBufferMin, hotelName, hotelLng, hotelLat, preferTransit, null, null);
    }

    public RouteConstraints {
        if (days < 1) {
            throw new IllegalArgumentException("days 必须 ≥1，实际: " + days);
        }
        if (maxDailyCommuteMin <= 0) {
            throw new IllegalArgumentException("maxDailyCommuteMin 必须 >0，实际: " + maxDailyCommuteMin);
        }
        if (transferBufferMin < 0) {
            throw new IllegalArgumentException("transferBufferMin 不能为负，实际: " + transferBufferMin);
        }
        if (dailyStart == null || dailyEnd == null || dailyStart.compareTo(dailyEnd) >= 0) {
            throw new IllegalArgumentException(
                    "dailyStart 必须早于 dailyEnd: " + dailyStart + " ~ " + dailyEnd);
        }
        boolean lunchComplete = lunchStart != null && lunchEnd != null;
        boolean lunchEmpty = lunchStart == null && lunchEnd == null;
        if (!lunchComplete && !lunchEmpty) {
            throw new IllegalArgumentException(
                    "午餐窗（lunchStart/lunchEnd）必须同时提供或同时缺省（皆空=不预留）: "
                            + lunchStart + " ~ " + lunchEnd);
        }
        if (lunchComplete && lunchStart.compareTo(lunchEnd) >= 0) {
            throw new IllegalArgumentException(
                    "lunchStart 必须早于 lunchEnd: " + lunchStart + " ~ " + lunchEnd);
        }
        boolean hotelComplete = hotelName != null && hotelLng != null && hotelLat != null;
        boolean hotelEmpty = hotelName == null && hotelLng == null && hotelLat == null;
        if (!hotelComplete && !hotelEmpty) {
            throw new IllegalArgumentException("酒店三件套（name/lng/lat）必须同时提供或同时缺省");
        }
    }

    /** 默认约束：09:00-21:00 日间窗、午餐窗 12:00-13:00、单日通勤 ≤90min、转场缓冲 10min、公交优先、无固定酒店 */
    public static RouteConstraints ofDays(int days) {
        return new RouteConstraints(days, "09:00", "21:00", "12:00", "13:00",
                90, 10, null, null, null, true);
    }

    /** 复制并设置酒店（VRPTW 车辆起点/终点） */
    public RouteConstraints withHotel(String name, double lng, double lat) {
        return new RouteConstraints(days, dailyStart, dailyEnd, lunchStart, lunchEnd,
                maxDailyCommuteMin, transferBufferMin, name, lng, lat, preferTransit,
                pace, walkingTolerance);
    }

    /** 复制并切换通勤方式偏好 */
    public RouteConstraints withPreferTransit(boolean prefer) {
        return new RouteConstraints(days, dailyStart, dailyEnd, lunchStart, lunchEnd,
                maxDailyCommuteMin, transferBufferMin, hotelName, hotelLng, hotelLat, prefer,
                pace, walkingTolerance);
    }

    /** 复制并设置午餐窗（传 null,null 关闭——不预留午餐时段） */
    public RouteConstraints withLunchWindow(String start, String end) {
        return new RouteConstraints(days, dailyStart, dailyEnd, start, end,
                maxDailyCommuteMin, transferBufferMin, hotelName, hotelLng, hotelLat, preferTransit,
                pace, walkingTolerance);
    }

    /** 从需求契约透传节奏约束；当前求解器只使用时间窗，pace/walking 仅保留在请求 DTO。 */
    public RouteConstraints withTravelPreference(String start, String end,
                                                 String pace, String walkingTolerance) {
        return new RouteConstraints(days, start, end, lunchStart, lunchEnd,
                maxDailyCommuteMin, transferBufferMin, hotelName, hotelLng, hotelLat,
                preferTransit, pace, walkingTolerance);
    }
}
