package com.travelscope.service.route;

import com.travelscope.service.route.dto.PoiCandidate;
import com.travelscope.service.route.dto.RouteConstraints;
import com.travelscope.service.route.dto.RouteSolveResult;
import com.travelscope.service.route.dto.TimeMatrix;

import java.util.ArrayList;
import java.util.List;

/**
 * 最近邻分日构造（S1.3，VRPTW 构造阶段）：把 POI 贪心分入 days 个日组。
 * <p>
 * 算法：全局贪心——每轮从全部未访问 POI 中选出「通勤最短（并列时该天通勤余量最大）」
 * 的（POI, 天）组合插入，天然实现多日均衡（余量大的天优先承接，避免前两天塞满
 * 最后一天空跑）。
 * </p>
 * <p>
 * 硬约束（任意插入均须满足，不可违反）：
 * <ul>
 *   <li>当日累计通勤 + 本次通勤 ≤ maxDailyCommuteMin</li>
 *   <li>开放窗兼容：预计到达 ≥ openStart 且 到达+游玩 ≤ openClose（皆空=全天兼容）；
 *       到达时刻 = 上一节点结束 + 通勤 + transferBufferMin</li>
 *   <li>当日链路末端不超 dailyEnd</li>
 *   <li>矩阵单元 -1（查询失败）→ 该组合不可行；与所有节点均不可达 → MATRIX_MISSING</li>
 * </ul>
 * 酒店锚：constraints 有酒店三件套时作为每日序列首节点（通勤链从酒店起算）；
 * 无酒店时空天首个 POI 通勤记 0。游玩时长缺省 120min。
 * 纯函数组件：无 Spring、无 LLM、无外部状态。
 * </p>
 */
public class DaySplitter {

    /** 游玩时长缺省值（分钟） */
    static final int DEFAULT_DURATION_MIN = 120;

    /**
     * 分日构造。
     *
     * @return SplitOutcome：days 为有序日组（每日含酒店起点若有），unplaced 为未排入项；
     *         不变式——POI 在 days 与 unplaced 合集中恰好出现一次
     */
    public SplitOutcome split(List<PoiCandidate> pois, TimeMatrix matrix, RouteConstraints c) {
        boolean hasHotel = c.hotelName() != null && c.hotelLng() != null && c.hotelLat() != null;
        PoiCandidate hotel = hasHotel
                ? new PoiCandidate(c.hotelName(), c.hotelLng(), c.hotelLat(),
                        null, null, null, null, "hotel-anchor")
                : null;

        // 节点表：[0]=酒店（若有），[1..]=POI；矩阵下标与之一致
        List<PoiCandidate> nodes = new ArrayList<>();
        if (hotel != null) {
            nodes.add(hotel);
        }
        nodes.addAll(pois);

        List<DayAcc> days = new ArrayList<>();
        for (int d = 0; d < c.days(); d++) {
            days.add(new DayAcc(d + 1, hotel, c));
        }

        List<Integer> pending = new ArrayList<>();
        for (int i = (hotel != null ? 1 : 0); i < nodes.size(); i++) {
            pending.add(i);
        }
        List<RouteSolveResult.UnplacedPoi> unplaced = new ArrayList<>();

        // 轮外硬拒：游玩时长超开窗（永远不可能）直接落 unplaced
        pending.removeIf(idx -> {
            RouteSolveResult.UnplacedReason reject = hardReject(nodes.get(idx));
            if (reject != null) {
                unplaced.add(new RouteSolveResult.UnplacedPoi(nodes.get(idx).name(), reject));
                return true;
            }
            return false;
        });

        while (!pending.isEmpty()) {
            Insertion best = null;
            for (int idx : pending) {
                for (DayAcc day : days) {
                    Insertion ins = day.tryInsert(nodes, idx, matrix, c);
                    if (ins == null) {
                        continue;
                    }
                    if (best == null
                            || ins.commuteSec < best.commuteSec
                            || (ins.commuteSec == best.commuteSec && ins.slackMin > best.slackMin)) {
                        best = ins;
                    }
                }
            }
            if (best == null) {
                break;
            }
            best.day.commit(best, nodes.get(best.poiIndex), c);
            int finalIdx = best.poiIndex;
            pending.removeIf(idx -> idx == finalIdx);
        }

        // 主循环结束后分类剩余点：与所有已落位节点均不可达 → MATRIX_MISSING；否则 COMMUTE_OVERFLOW
        List<Integer> placedIndices = new ArrayList<>();
        for (DayAcc day : days) {
            placedIndices.addAll(day.nodeIndices());
        }
        for (int idx : pending) {
            boolean matrixMissing = placedIndices.isEmpty()
                    || placedIndices.stream().allMatch(pi -> matrix.get(pi, idx) < 0 && matrix.get(idx, pi) < 0);
            unplaced.add(new RouteSolveResult.UnplacedPoi(nodes.get(idx).name(),
                    matrixMissing
                            ? RouteSolveResult.UnplacedReason.MATRIX_MISSING
                            : RouteSolveResult.UnplacedReason.COMMUTE_OVERFLOW));
        }

        return new SplitOutcome(days, List.copyOf(unplaced));
    }

    /** 轮外硬拒：只保留开放窗本身不可行（永远不可能，无歧义） */
    private RouteSolveResult.UnplacedReason hardReject(PoiCandidate p) {
        if (p.openStart() != null && p.openClose() != null && p.suggestedDurationMin() != null) {
            int window = minutesOf(p.openClose()) - minutesOf(p.openStart());
            if (p.suggestedDurationMin() > window) {
                return RouteSolveResult.UnplacedReason.TIME_WINDOW_MISS;
            }
        }
        return null;
    }

    /** 单日累加器：有序 POI 序列（含酒店起点）+ 通勤总量 + 链路末端时刻 */
    public static final class DayAcc {

        private final int dayNumber;
        private final List<PoiCandidate> sequence = new ArrayList<>();
        private final List<Integer> nodeIndices = new ArrayList<>();
        private final PoiCandidate hotel;
        private int commuteSecTotal;
        private int endMinute;

        DayAcc(int dayNumber, PoiCandidate hotel, RouteConstraints c) {
            this.dayNumber = dayNumber;
            this.hotel = hotel;
            this.endMinute = minutesOf(c.dailyStart());
            if (hotel != null) {
                sequence.add(hotel);
            }
        }

        public int dayNumber() {
            return dayNumber;
        }

        /** 当日有序节点序列（含酒店起点，若有） */
        public List<PoiCandidate> sequence() {
            return List.copyOf(sequence);
        }

        /** 当日通勤总量（分钟，四舍五入） */
        public int commuteMinutesTotal() {
            return (int) Math.round(commuteSecTotal / 60.0);
        }

        /** 当日通勤总量（秒，精确值） */
        public int commuteSecTotal() {
            return commuteSecTotal;
        }

        /** 当日链路末端时刻（当日分钟数），供 S1.5 顺排起点 */
        public int endMinute() {
            return endMinute;
        }

        /** 当日已落位节点的矩阵下标（供未排入点分类） */
        List<Integer> nodeIndices() {
            return List.copyOf(nodeIndices);
        }

        /** 当前通勤余量（分钟） */
        int slackMinutes(RouteConstraints c) {
            return c.maxDailyCommuteMin() - commuteMinutesTotal();
        }

        private PoiCandidate lastNode() {
            return sequence.isEmpty() ? null : sequence.get(sequence.size() - 1);
        }

        /** 试探插入：返回可行方案，不可行返回 null */
        Insertion tryInsert(List<PoiCandidate> nodes, int idx, TimeMatrix matrix, RouteConstraints c) {
            PoiCandidate candidate = nodes.get(idx);
            long commuteSec;
            int arriveMinute;
            if (lastNode() == null) {
                // 空天且无酒店锚：首个 POI 通勤记 0
                commuteSec = 0;
                arriveMinute = minutesOf(c.dailyStart());
            } else {
                int fromIdx = indexOf(lastNode(), nodes);
                long m = matrix.get(fromIdx, idx);
                if (m < 0) {
                    return null;
                }
                commuteSec = m;
                arriveMinute = endMinute + (int) Math.round(commuteSec / 60.0) + c.transferBufferMin();
            }
            // 硬约束 1：通勤上限
            if (commuteMinutesTotal() + Math.round(commuteSec / 60.0) > c.maxDailyCommuteMin()) {
                return null;
            }
            // 硬约束 2：开放窗兼容
            int duration = durationOf(candidate);
            if (!windowCompatible(candidate, arriveMinute, duration)) {
                return null;
            }
            // 硬约束 3：当日链路不超 dailyEnd
            if (arriveMinute + duration > minutesOf(c.dailyEnd())) {
                return null;
            }
            return new Insertion(this, idx, commuteSec, slackMinutes(c), arriveMinute, duration);
        }

        void commit(Insertion ins, PoiCandidate poi, RouteConstraints c) {
            sequence.add(poi);
            nodeIndices.add(ins.poiIndex);
            commuteSecTotal += ins.commuteSec;
            endMinute = ins.arriveMinute + ins.durationMin;
        }

        private static int indexOf(PoiCandidate node, List<PoiCandidate> nodes) {
            for (int i = 0; i < nodes.size(); i++) {
                if (nodes.get(i) == node) {
                    return i;
                }
            }
            throw new IllegalStateException("末节点不在节点表中: " + node.name());
        }
    }

    /** 一次可行插入方案 */
    private record Insertion(DayAcc day, int poiIndex, long commuteSec, int slackMin,
                             int arriveMinute, int durationMin) {
    }

    /** 分日结果：日组 + 未排入 */
    public record SplitOutcome(List<DayAcc> days, List<RouteSolveResult.UnplacedPoi> unplaced) {
    }

    /** 开放窗兼容：到达 ≥ openStart 且 到达+游玩 ≤ openClose（皆空=全天兼容） */
    private static boolean windowCompatible(PoiCandidate p, int arriveMinute, int durationMin) {
        if (p.openStart() == null || p.openClose() == null) {
            return true;
        }
        return arriveMinute >= minutesOf(p.openStart())
                && arriveMinute + durationMin <= minutesOf(p.openClose());
    }

    private static int durationOf(PoiCandidate p) {
        return p.suggestedDurationMin() == null ? DEFAULT_DURATION_MIN : p.suggestedDurationMin();
    }

    /** "HH:mm" → 当日分钟数 */
    static int minutesOf(String hhmm) {
        String[] parts = hhmm.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }
}
