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
        int maxDailyCommuteMin,
        int transferBufferMin,
        String hotelName,
        Double hotelLng,
        Double hotelLat,
        boolean preferTransit) {

    public RouteConstraints {
        if (days < 1) {
            throw new IllegalArgumentException("days 必须 ≥1，实际: " + days);
        }
        if (maxDailyCommuteMin <= 0) {
            throw new IllegalArgumentException("maxDailyCommuteMin 必须 >0，实际: " + maxDailyCommuteMin);
        }
        if (transferBufferMin < 0) {
            throw new IllegalArgumentException("transferBufferMin 不能为负: " + transferBufferMin);
        }
        if (dailyStart == null || dailyEnd == null || dailyStart.compareTo(dailyEnd) >= 0) {
            throw new IllegalArgumentException(
                    "dailyStart 必须早于 dailyEnd: " + dailyStart + " ~ " + dailyEnd);
        }
        boolean hotelComplete = hotelName != null && hotelLng != null && hotelLat != null;
        boolean hotelEmpty = hotelName == null && hotelLng == null && hotelLat == null;
        if (!hotelComplete && !hotelEmpty) {
            throw new IllegalArgumentException("酒店三件套（name/lng/lat）必须同时提供或同时缺省");
        }
    }

    /** 默认约束：09:00-21:00 日间窗、单日通勤 ≤90min、转场缓冲 10min、公交优先、无固定酒店 */
    public static RouteConstraints ofDays(int days) {
        return new RouteConstraints(days, "09:00", "21:00", 90, 10, null, null, null, true);
    }

    /** 复制并设置酒店（VRPTW 车辆起点/终点） */
    public RouteConstraints withHotel(String name, double lng, double lat) {
        return new RouteConstraints(days, dailyStart, dailyEnd, maxDailyCommuteMin,
                transferBufferMin, name, lng, lat, preferTransit);
    }

    /** 复制并切换通勤方式偏好 */
    public RouteConstraints withPreferTransit(boolean prefer) {
        return new RouteConstraints(days, dailyStart, dailyEnd, maxDailyCommuteMin,
                transferBufferMin, hotelName, hotelLng, hotelLat, prefer);
    }
}
