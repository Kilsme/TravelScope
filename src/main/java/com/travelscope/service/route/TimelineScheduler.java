package com.travelscope.service.route;

import com.travelscope.service.route.dto.PoiCandidate;
import com.travelscope.service.route.dto.RouteConstraints;
import com.travelscope.service.route.dto.RouteSolveResult;
import com.travelscope.service.route.dto.TimeMatrix;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 时段顺排（S1.5）：对单日有序序列从 dailyStart 起顺排时间轴，产出每个 POI 的
 * {startTime, endTime, transitFromPrev, commuteMinutesFromPrev}。
 * <p>
 * 时间线语义与 {@link DaySplitter} 插入判定 / {@link TwoOptImprover} 反转判定
 * 逐字一致（共用 minutesOf/durationOf）：锚 = 下标 0 固定——有酒店锚时首 POI
 * 到达 = dailyStart + 通勤 + 缓冲；无酒店锚时首 POI 到达 = dailyStart（无通勤
 * 无缓冲），其余节点到达 = 上一项结束 + 通勤 + transferBuffer。酒店锚本身不是
 * 行程项，不产出 ScheduledPoi。
 * </p>
 * <p>
 * <b>午餐窗硬规则</b>（v4 计划 S1.5 增补）：[到达, 到达+游玩] 与
 * (lunchStart, lunchEnd) 相交（到达 &lt; lunchEnd 且 到达+游玩 &gt; lunchStart，
 * 端点相接不算相交）→ 到达顺延至 lunchEnd，后续链条随之顺延。午餐优先于
 * 开放窗时刻——顺延后如撞 openClose/dailyEnd，如实输出（该冲突对下游评审段
 * 可见，回炉可感知）；lunchStart/lunchEnd 皆空 = 不预留午餐。
 * </p>
 * <p>
 * commuteMinutesFromPrev = round(leg/60)（与 DaySplitter 插入判定同款取整），
 * 无酒店锚时当日首项为 null（契约「当日首项无前序通勤」）；
 * commuteMinutesTotal = Σ 各段分钟（恰好满足 CollabSchemaValidator 的
 * total ≤ Σ commuteMinutesFromPrev + 1 规则）。transitFromPrev 为交通方式
 * 展示标签（{@link CommuteMatrixBuilder#segmentModeLabel}）。
 * </p>
 * <p>
 * 纯函数组件：无 Spring、无外部状态；输入来自 DaySplitter/TwoOpt 链路（已可行），
 * 遇不可达（-1）边抛 IllegalStateException（上游契约破裂快速失败）。
 * </p>
 */
public class TimelineScheduler {

    /** 单日时间轴：有序 POI 时段 + 当日总通勤（分钟，= Σ 各段分钟） */
    public record DayTimeline(List<RouteSolveResult.ScheduledPoi> pois, int commuteMinutesTotal) {
    }

    /**
     * 顺排单日时间轴。
     *
     * @param daySequence 当日有序节点，下标 0 为起点锚（酒店或首个 POI）
     * @param matrix      通勤矩阵（须覆盖序列全部节点名）
     * @param constraints 求解约束（时间窗/午餐窗/缓冲/通勤方式偏好）
     * @return 有序时段列表（不含锚本身）+ 当日总通勤分钟
     */
    public DayTimeline schedule(List<PoiCandidate> daySequence, TimeMatrix matrix,
                                RouteConstraints constraints) {
        Objects.requireNonNull(daySequence, "daySequence");
        Objects.requireNonNull(matrix, "matrix");
        Objects.requireNonNull(constraints, "constraints");

        int n = daySequence.size();
        if (n == 0) {
            return new DayTimeline(List.of(), 0);
        }

        boolean anchorIsHotel = constraints.hotelName() != null
                && daySequence.get(0).name().equals(constraints.hotelName());
        int lunchStart = constraints.lunchStart() == null ? -1
                : DaySplitter.minutesOf(constraints.lunchStart());
        int lunchEnd = constraints.lunchEnd() == null ? -1
                : DaySplitter.minutesOf(constraints.lunchEnd());

        List<RouteSolveResult.ScheduledPoi> items = new ArrayList<>(n);
        int commuteMinutesTotal = 0;
        int endMinute = DaySplitter.minutesOf(constraints.dailyStart());

        for (int k = 0; k < n; k++) {
            PoiCandidate node = daySequence.get(k);
            if (k == 0 && anchorIsHotel) {
                continue;  // 酒店锚不是行程项，endMinute 保持 dailyStart
            }
            long legSec = 0;
            String transitFromPrev = null;
            Integer commuteFromPrev = null;
            if (k > 0) {
                PoiCandidate prev = daySequence.get(k - 1);
                legSec = matrix.get(matrix.indexOf(prev.name()), matrix.indexOf(node.name()));
                if (legSec < 0) {
                    throw new IllegalStateException(
                            "时间轴遇不可达边: " + prev.name() + "→" + node.name());
                }
                transitFromPrev = CommuteMatrixBuilder.segmentModeLabel(
                        prev, node, constraints.preferTransit());
                commuteFromPrev = (int) Math.round(legSec / 60.0);
                commuteMinutesTotal += commuteFromPrev;
            }
            int duration = DaySplitter.durationOf(node);
            int arrive = k == 0
                    ? endMinute
                    : endMinute + (int) Math.round(legSec / 60.0) + constraints.transferBufferMin();
            // 午餐窗硬规则：区间相交（端点相接不算）→ 顺延至 lunchEnd
            if (lunchStart >= 0 && arrive < lunchEnd && arrive + duration > lunchStart) {
                arrive = lunchEnd;
            }
            items.add(new RouteSolveResult.ScheduledPoi(node, hhmm(arrive),
                    hhmm(arrive + duration), transitFromPrev, commuteFromPrev));
            endMinute = arrive + duration;
        }
        return new DayTimeline(List.copyOf(items), commuteMinutesTotal);
    }

    /** 当日分钟数 → "HH:mm"（Locale.ROOT 防本地化数字变形） */
    private static String hhmm(int minute) {
        return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }
}
