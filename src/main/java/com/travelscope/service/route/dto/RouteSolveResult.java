package com.travelscope.service.route.dto;

import java.util.List;
import java.util.Objects;

/**
 * 路线求解结果（VRPTW 解）：按日有序 POI 序列 + 通勤明细 + 未排入项 + 求解元信息。
 * <p>
 * 不变式（由求解实现保证、测试断言）：每日 commuteMinutesTotal ≤ 约束上限；
 * 每个 POI 在 days 中至多出现一次；未排入项必带原因。
 * </p>
 */
public record RouteSolveResult(
        List<RouteDay> days,
        List<UnplacedPoi> unplaced,
        SolverMeta solverMeta) {

    public RouteSolveResult {
        days = List.copyOf(Objects.requireNonNull(days, "days"));
        unplaced = unplaced == null ? List.of() : List.copyOf(unplaced);
        Objects.requireNonNull(solverMeta, "solverMeta");
    }

    /** 单日解：有序 POI + 当日总通勤（分钟） */
    public record RouteDay(int day, List<ScheduledPoi> pois, int commuteMinutesTotal) {

        public RouteDay {
            if (day < 1) {
                throw new IllegalArgumentException("day 从 1 起，实际: " + day);
            }
            pois = List.copyOf(Objects.requireNonNull(pois, "pois"));
            if (commuteMinutesTotal < 0) {
                throw new IllegalArgumentException("commuteMinutesTotal 不能为负: " + commuteMinutesTotal);
            }
        }
    }

    /**
     * 已排程 POI：候选 + 时段（"HH:mm"）+ 前序通勤。
     * transitFromPrev/commuteMinutesFromPrev 可空（当日首项无前序通勤）。
     */
    public record ScheduledPoi(
            PoiCandidate candidate,
            String startTime,
            String endTime,
            String transitFromPrev,
            Integer commuteMinutesFromPrev) {

        public ScheduledPoi {
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(startTime, "startTime");
            Objects.requireNonNull(endTime, "endTime");
        }
    }

    /** 未排入 POI 及原因 */
    public record UnplacedPoi(String name, UnplacedReason reason) {

        public UnplacedPoi {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** 未排入原因：单日通勤装不下 / 开放时间窗不兼容 / 通勤矩阵缺失该点 */
    public enum UnplacedReason {
        COMMUTE_OVERFLOW, TIME_WINDOW_MISS, MATRIX_MISSING
    }

    /** 求解元信息：algorithm 如 "NN+2opt"；matrixSource 如 "amap-transit"/"amap-driving" */
    public record SolverMeta(String algorithm, String matrixSource, long durationMs) {

        public SolverMeta {
            Objects.requireNonNull(algorithm, "algorithm");
            Objects.requireNonNull(matrixSource, "matrixSource");
            if (durationMs < 0) {
                throw new IllegalArgumentException("durationMs 不能为负: " + durationMs);
            }
        }
    }
}
