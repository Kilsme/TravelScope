package com.travelscope.service.route.dto;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

/** POI 开放状态；UNKNOWN 明确表示缺数据，绝不等价于全天开放。 */
public record OpenWindowPolicy(OpenStatus status, List<TimeWindow> windows,
                               String source, Instant fetchedAt) {
    public OpenWindowPolicy {
        Objects.requireNonNull(status, "status");
        windows = List.copyOf(Objects.requireNonNull(windows, "windows"));
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        if ((status == OpenStatus.KNOWN_OPEN || status == OpenStatus.KNOWN_24H
                || status == OpenStatus.DAYLIGHT_ONLY) && windows.isEmpty()) {
            throw new IllegalArgumentException(status + " 必须包含开放窗口");
        }
        if (status == OpenStatus.UNKNOWN && !windows.isEmpty()) {
            throw new IllegalArgumentException("UNKNOWN 不得伪造开放窗口");
        }
    }

    public enum OpenStatus { KNOWN_OPEN, KNOWN_24H, UNKNOWN, DAYLIGHT_ONLY }

    public record TimeWindow(String start, String end) {
        public TimeWindow {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (!LocalTime.parse(start).isBefore(LocalTime.parse(end))) {
                throw new IllegalArgumentException("开放窗口开始时间必须早于结束时间");
            }
        }
    }
}
