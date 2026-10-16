package com.travelscope.service.route;

import com.travelscope.service.route.dto.TimeMatrix;

import java.util.List;
import java.util.Objects;

/**
 * 通勤矩阵构建结果（S1.2）：矩阵 + 缺失对 + 调用统计。
 * <p>
 * missingPairs 元素形如 "A→B"（该对两方式查询均失败，矩阵对应单元为 -1）；
 * apiCalls 为本次实际发出的高德 API 数（不含缓存命中）；cacheHit=true 表示
 * 整矩阵命中缓存（本次零 API 调用）。
 * </p>
 */
public record MatrixBuildOutcome(
        TimeMatrix matrix,
        List<String> missingPairs,
        int apiCalls,
        boolean cacheHit) {

    public MatrixBuildOutcome {
        Objects.requireNonNull(matrix, "matrix");
        missingPairs = List.copyOf(Objects.requireNonNull(missingPairs, "missingPairs"));
        if (apiCalls < 0) {
            throw new IllegalArgumentException("apiCalls 不能为负: " + apiCalls);
        }
    }
}
