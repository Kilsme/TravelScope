package com.travelscope.service.route;

import com.travelscope.service.route.dto.OpenWindowPolicy;
import com.travelscope.service.route.dto.PoiCandidate;
import com.travelscope.service.route.dto.RouteConstraints;

import java.time.LocalTime;

/** 将开放策略转换为求解器有效窗口：日落门禁、闭馆缓冲和未知时间保守窗口。 */
public final class RouteWindowPolicyApplier {
    static final int CLOSING_BUFFER_MINUTES = 30;
    private final SunTimeProvider sunTimeProvider;

    public RouteWindowPolicyApplier(SunTimeProvider sunTimeProvider) {
        this.sunTimeProvider = java.util.Objects.requireNonNull(sunTimeProvider, "sunTimeProvider");
    }

    public PoiCandidate apply(PoiCandidate poi, RouteConstraints constraints, String city) {
        OpenWindowPolicy.OpenStatus status = poi.openPolicy().status();
        String start = poi.openStart();
        String close = poi.openClose();
        String note = poi.scheduleNote();
        if (status == OpenWindowPolicy.OpenStatus.DAYLIGHT_ONLY) {
            LocalTime sunset = sunTimeProvider.sunset(city);
            if (sunset == null) sunset = LocalTime.of(18, 0);
            LocalTime configuredClose = close == null ? sunset : LocalTime.parse(close);
            close = min(configuredClose, sunset).toString();
            start = start == null ? constraints.dailyStart() : start;
        } else if (status == OpenWindowPolicy.OpenStatus.KNOWN_OPEN) {
            LocalTime buffered = LocalTime.parse(close).minusMinutes(CLOSING_BUFFER_MINUTES);
            if (buffered.isAfter(LocalTime.parse(start))) close = buffered.toString();
        } else if (status == OpenWindowPolicy.OpenStatus.UNKNOWN) {
            if (isNightActivity(poi.name())) {
                start = max(LocalTime.parse(constraints.dailyStart()), LocalTime.of(18, 0)).toString();
                close = constraints.dailyEnd();
            } else {
                start = max(LocalTime.parse(constraints.dailyStart()), LocalTime.of(9, 0)).toString();
                close = min(LocalTime.parse(constraints.dailyEnd()), LocalTime.of(18, 0)).toString();
            }
            note = "开放时间未知，采用保守窗口，待确认";
        }
        return new PoiCandidate(poi.name(), poi.lng(), poi.lat(), start, close,
                poi.suggestedDurationMin(), poi.rating(), poi.source(), poi.openPolicy(), note);
    }

    private static boolean isNightActivity(String name) {
        return containsAny(name, "夜景", "夜游", "音乐喷泉", "喷泉", "灯光", "灯会", "夜市", "不夜城", "夜场");
    }

    private static boolean containsAny(String value, String... tokens) {
        for (String token : tokens) if (value.contains(token)) return true;
        return false;
    }

    private static LocalTime min(LocalTime a, LocalTime b) { return a.isBefore(b) ? a : b; }
    private static LocalTime max(LocalTime a, LocalTime b) { return a.isAfter(b) ? a : b; }
}
