package com.travelscope.service.route.dto;

import java.util.Objects;

/**
 * 路线求解输入：POI 候选节点（VRPTW 中的「带时间窗客户点」）。
 * <p>
 * lng/lat 可空——由 CommuteMatrixBuilder（S1.2）经 geocode 补齐后才进矩阵构建；
 * openStart/openClose 即 VRPTW 时间窗（景点开放时段），必须成对出现；
 * openPolicy 显式区分 UNKNOWN 与 KNOWN_24H，禁止再由空窗口推断全天开放。
 * </p>
 */
public record PoiCandidate(
        String name,
        Double lng,
        Double lat,
        String openStart,
        String openClose,
        Integer suggestedDurationMin,
        Double rating,
        String source,
        OpenWindowPolicy openPolicy,
        String scheduleNote) {

    /** 旧调用兼容：成对窗口视为 KNOWN_OPEN，皆空保留为 UNKNOWN，不再推断全天。 */
    public PoiCandidate(String name, Double lng, Double lat, String openStart, String openClose,
                        Integer suggestedDurationMin, Double rating, String source) {
        this(name, lng, lat, openStart, openClose, suggestedDurationMin, rating, source,
                legacyPolicy(openStart, openClose, source), null);
    }

    public PoiCandidate(String name, Double lng, Double lat, String openStart, String openClose,
                        Integer suggestedDurationMin, Double rating, String source,
                        OpenWindowPolicy openPolicy) {
        this(name, lng, lat, openStart, openClose, suggestedDurationMin, rating, source,
                openPolicy, null);
    }

    public PoiCandidate {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(openPolicy, "openPolicy");
        if ((openStart == null) != (openClose == null)) {
            throw new IllegalArgumentException(
                    "openStart/openClose 必须成对出现（皆空时由 openPolicy 区分 UNKNOWN/24H）: " + name
                            + " openStart=" + openStart + " openClose=" + openClose);
        }
        if (suggestedDurationMin != null && suggestedDurationMin <= 0) {
            throw new IllegalArgumentException("suggestedDurationMin 必须为正: " + name);
        }
    }

    private static OpenWindowPolicy legacyPolicy(String start, String end, String source) {
        String origin = source == null ? "UNKNOWN" : source;
        if (start == null && end == null) {
            return new OpenWindowPolicy(OpenWindowPolicy.OpenStatus.UNKNOWN, java.util.List.of(),
                    origin, java.time.Instant.EPOCH);
        }
        if (start == null || end == null) {
            throw new IllegalArgumentException("openStart/openClose 必须成对出现");
        }
        return new OpenWindowPolicy(OpenWindowPolicy.OpenStatus.KNOWN_OPEN,
                java.util.List.of(new OpenWindowPolicy.TimeWindow(normalizeTime(start), normalizeTime(end))),
                origin, java.time.Instant.EPOCH);
    }

    private static String normalizeTime(String value) {
        return value.length() == 4 ? "0" + value : value;
    }
}
