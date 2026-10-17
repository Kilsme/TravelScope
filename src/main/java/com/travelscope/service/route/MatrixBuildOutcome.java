package com.travelscope.service.route;

import com.travelscope.service.route.dto.PoiCandidate;
import com.travelscope.service.route.dto.TimeMatrix;

import java.util.List;
import java.util.Objects;

/**
 * 通勤矩阵构建结果（S1.2）：矩阵 + 节点表 + 缺失对 + 调用统计。
 * <p>
 * missingPairs 元素形如 "A→B"（该对两方式查询均失败，矩阵对应单元为 -1）；
 * apiCalls 为本次实际发出的高德 API 数（不含缓存命中）；cacheHit=true 表示
 * 整矩阵命中缓存（本次零 API 调用）。
 * </p>
 * <p>
 * nodes（S1.5 增补）为坐标已补齐的节点表，顺序与矩阵行列一致（含调用方加入的
 * 酒店锚）——geocode 补齐结果由此流出，下游排线/落盘直接消费，保证 RoutePoi
 * 坐标必填契约成立。缓存命中路径的 nodes 取本次入参（坐标本就齐备或已由 GEO
 * 缓存补齐后再进 build——矩阵缓存命中不绕过坐标补齐）。
 * </p>
 */
public record MatrixBuildOutcome(
        TimeMatrix matrix,
        List<PoiCandidate> nodes,
        List<String> missingPairs,
        int apiCalls,
        boolean cacheHit) {

    public MatrixBuildOutcome {
        Objects.requireNonNull(matrix, "matrix");
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        if (nodes.size() != matrix.size()) {
            throw new IllegalArgumentException(
                    "节点数(" + nodes.size() + ")与矩阵维数(" + matrix.size() + ")不一致");
        }
        missingPairs = List.copyOf(Objects.requireNonNull(missingPairs, "missingPairs"));
        if (apiCalls < 0) {
            throw new IllegalArgumentException("apiCalls 不能为负: " + apiCalls);
        }
    }
}
