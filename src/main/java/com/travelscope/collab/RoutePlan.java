package com.travelscope.collab;

import java.util.List;
import java.util.Objects;

/**
 * 工作区契约：route_plan.json（schemaVersion=4）
 * <p>
 * 生产者：RoutePlannerService（代码求解，零 LLM）；消费者：itinerary-agent / reviewer-agent。
 * 对应 v3 的 route_plan.md。求解语义对齐 VRPTW（时间矩阵 / 开放时间窗 / 车辆=天），
 * 接口演进路径见 docs/TravelScope重构计划-v4.md §5.2。
 * </p>
 * <p>
 * schemaVersion 恒为 {@link #SCHEMA_VERSION}（紧凑构造器强校验）；JSON 键与组件同名。
 * </p>
 */
public record RoutePlan(
        int schemaVersion,
        List<RouteDay> days,
        SolverMeta solverMeta) {

    /** 契约版本 */
    public static final int SCHEMA_VERSION = 4;

    public RoutePlan {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "schemaVersion 必须为 " + SCHEMA_VERSION + "，实际: " + schemaVersion);
        }
        days = List.copyOf(Objects.requireNonNull(days, "days"));
        Objects.requireNonNull(solverMeta, "solverMeta");
    }

    /** 便捷构造：schemaVersion 固定为 {@link #SCHEMA_VERSION} */
    public RoutePlan(List<RouteDay> days, SolverMeta solverMeta) {
        this(SCHEMA_VERSION, days, solverMeta);
    }

    /**
     * 单日路线。day 从 1 起；date 可空（出发日期未定时）；commuteMinutesTotal 为当日各段通勤之和。
     * <p>
     * inputFingerprint（R2 增量重组装，可空）：当日 POI 输入指纹（排序后的 name@坐标 SHA-256 前 16 位）。
     * 增量轮按它识别「输入未变的天」并字节级复用旧条目；存量产物缺键为 null（按 RoutePoi 现算兼容）。
     * </p>
     */
    public record RouteDay(
            int day,
            String date,
            List<RoutePoi> pois,
            int commuteMinutesTotal,
            String inputFingerprint) {

        public RouteDay {
            if (day < 1) {
                throw new IllegalArgumentException("day 从 1 起，实际: " + day);
            }
            pois = List.copyOf(Objects.requireNonNull(pois, "pois"));
            if (commuteMinutesTotal < 0) {
                throw new IllegalArgumentException("commuteMinutesTotal 不能为负: " + commuteMinutesTotal);
            }
            // inputFingerprint 可空（存量产物未记录）
        }

        /** 兼容构造（v4 原签名）：不记输入指纹（增量轮按 RoutePoi 现算匹配） */
        public RouteDay(int day, String date, List<RoutePoi> pois, int commuteMinutesTotal) {
            this(day, date, pois, commuteMinutesTotal, null);
        }
    }

    /**
     * 有序 POI 节点。startTime/endTime 为 "HH:mm"；坐标必填（求解前已由 geocode 补齐）。
     * transitFromPrev/commuteMinutesFromPrev 可空（当日首项无前序通勤）。
     */
    public record RoutePoi(
            String name,
            Double lng,
            Double lat,
            String startTime,
            String endTime,
            String transitFromPrev,
            Integer commuteMinutesFromPrev) {

        public RoutePoi {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(lng, "lng");
            Objects.requireNonNull(lat, "lat");
            Objects.requireNonNull(startTime, "startTime");
            Objects.requireNonNull(endTime, "endTime");
        }
    }

    /** 求解器元信息。algorithm 如 "NN+2opt"；matrixSource 如 "amap-transit"/"amap-driving"。 */
    public record SolverMeta(
            String algorithm,
            String matrixSource,
            long durationMs,
            List<UnplacedPoi> unplaced) {

        public SolverMeta {
            Objects.requireNonNull(algorithm, "algorithm");
            Objects.requireNonNull(matrixSource, "matrixSource");
            if (durationMs < 0) {
                throw new IllegalArgumentException("durationMs 不能为负: " + durationMs);
            }
            unplaced = unplaced == null ? List.of() : List.copyOf(unplaced);
        }
    }

    /** 未能排入的 POI。reason 取值 COMMUTE_OVERFLOW / TIME_WINDOW_MISS / MATRIX_MISSING。 */
    public record UnplacedPoi(String name, String reason) {

        public UnplacedPoi {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
