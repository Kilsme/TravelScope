package com.travelscope.service.route;

import com.travelscope.service.route.dto.OpenWindowPolicy;
import com.travelscope.service.route.dto.PoiCandidate;
import com.travelscope.service.route.dto.RouteConstraints;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouteWindowPolicyApplierTest {
    private final RouteWindowPolicyApplier applier =
            new RouteWindowPolicyApplier(city -> LocalTime.of(17, 45));

    @Test
    void conv102JiuxiCannotRunAfterFixedSunset() {
        PoiCandidate jiuxi = candidate("九溪烟树", OpenWindowPolicy.OpenStatus.DAYLIGHT_ONLY,
                "08:00", "18:00", 60);
        PoiCandidate effective = applier.apply(jiuxi, RouteConstraints.ofDays(1), "杭州");

        assertThat(effective.openClose()).isEqualTo("17:45");
        assertThat(DaySplitter.windowCompatible(effective, 17 * 60, 60)).isFalse();
    }

    @Test
    void westLakeNightViewCanBeScheduledInEvening() {
        PoiCandidate night = candidate("西湖音乐喷泉夜景", OpenWindowPolicy.OpenStatus.UNKNOWN,
                null, null, 60);
        PoiCandidate effective = applier.apply(night, RouteConstraints.ofDays(1), "杭州");

        assertThat(effective.openStart()).isEqualTo("18:00");
        assertThat(effective.openClose()).isEqualTo("21:00");
        assertThat(effective.scheduleNote()).contains("待确认");
        assertThat(DaySplitter.windowCompatible(effective, 19 * 60, 60)).isTrue();
        assertThat(DaySplitter.serviceStart(effective, 15 * 60)).isEqualTo(18 * 60);
    }

    @Test
    void museumMustFinishBeforeClosingBuffer() {
        PoiCandidate museum = candidate("杭州博物馆", OpenWindowPolicy.OpenStatus.KNOWN_OPEN,
                "09:00", "17:00", 60);
        PoiCandidate effective = applier.apply(museum, RouteConstraints.ofDays(1), "杭州");

        assertThat(effective.openClose()).isEqualTo("16:30");
        assertThat(DaySplitter.windowCompatible(effective, 16 * 60, 60)).isFalse();
        assertThat(DaySplitter.windowCompatible(effective, 15 * 60 + 30, 60)).isTrue();
    }

    @Test
    void unknownHoursUseConservativeWindowAndRequireConfirmation() {
        PoiCandidate unknown = candidate("室内展馆", OpenWindowPolicy.OpenStatus.UNKNOWN,
                null, null, 90);
        PoiCandidate effective = applier.apply(unknown, RouteConstraints.ofDays(1), "杭州");

        assertThat(effective.openStart()).isEqualTo("09:00");
        assertThat(effective.openClose()).isEqualTo("18:00");
        assertThat(effective.scheduleNote()).isEqualTo("开放时间未知，采用保守窗口，待确认");
    }

    private static PoiCandidate candidate(String name, OpenWindowPolicy.OpenStatus status,
                                          String start, String end, int duration) {
        List<OpenWindowPolicy.TimeWindow> windows = start == null ? List.of()
                : List.of(new OpenWindowPolicy.TimeWindow(start, end));
        OpenWindowPolicy policy = new OpenWindowPolicy(status, windows, "fixture", Instant.EPOCH);
        return new PoiCandidate(name, 120.1, 30.2, start, end, duration, 4.5, "fixture", policy, null);
    }
}
