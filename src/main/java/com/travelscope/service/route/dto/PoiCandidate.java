package com.travelscope.service.route.dto;

import java.util.Objects;

/**
 * 路线求解输入：POI 候选节点（VRPTW 中的「带时间窗客户点」）。
 * <p>
 * lng/lat 可空——由 CommuteMatrixBuilder（S1.2）经 geocode 补齐后才进矩阵构建；
 * openStart/openClose 即 VRPTW 时间窗（景点开放时段），必须成对出现，
 * 单一残缺视为数据错误直接拒绝；两者皆空 = 全天开放。
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
        String source) {

    public PoiCandidate {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
        if ((openStart == null) != (openClose == null)) {
            throw new IllegalArgumentException(
                    "openStart/openClose 必须成对出现（皆空=全天开放）: " + name
                            + " openStart=" + openStart + " openClose=" + openClose);
        }
        if (suggestedDurationMin != null && suggestedDurationMin <= 0) {
            throw new IllegalArgumentException("suggestedDurationMin 必须为正: " + name);
        }
    }
}
