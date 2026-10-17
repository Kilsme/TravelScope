package com.travelscope.service.route;

import com.travelscope.service.route.dto.PoiCandidate;
import com.travelscope.service.route.dto.RouteConstraints;
import com.travelscope.service.route.dto.TimeMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 2-opt 局部优化（S1.4，VRPTW 局部搜索阶段）：对单日有序节点序列做经典 2-opt
 * 区间反转，当日总通勤只降不升。
 * <p>
 * 算法（最陡下降变体）：每轮遍历全部 (i,j) 反转对（1 ≤ i &lt; j ≤ n-1，下标 0 的
 * 起点锚固定不动——有酒店时酒店是 VRPTW 车辆起点，无酒店时首个 POI 按惯例入边
 * 通勤记 0，两者移位都会改变语义，不参与反转），取「总通勤严格变小且可行」中
 * 改进量最大的一个应用；重复至无改进或达 {@link #MAX_PASSES} 轮上限。每轮严格
 * 下降保证收敛，也保证「只降不升」不变式。
 * </p>
 * <p>
 * 硬约束保护（v4 计划 §5.2 第 4 步）：每个候选反转后整条重算时间线——开放窗
 * 兼容（到达 ≥ openStart 且 到达+游玩 ≤ openClose）、链路末端 ≤ dailyEnd、
 * 单日通勤 ≤ maxDailyCommuteMin、矩阵单元 -1（不可达）——任一违反即跳过该候选。
 * 时间线语义与 {@link DaySplitter} 的插入判定逐字一致（共用 minutesOf /
 * windowCompatible / durationOf），2-opt 不会产出 DaySplitter 自己都会拒绝的序列。
 * </p>
 * <p>
 * 复杂度：每轮 O(n²) 个候选 × O(n) 约束重算，100 轮上限，n ≤ 16 时毫秒级——
 * 零外部依赖。纯函数组件：无 Spring、无 LLM、无外部状态。
 * </p>
 */
public class TwoOptImprover {

    private static final Logger log = LoggerFactory.getLogger(TwoOptImprover.class);

    /** 最大迭代轮数（每轮应用一个最优改进反转） */
    private static final int MAX_PASSES = 100;

    /**
     * 优化单日序列。
     *
     * @param daySequence 当日有序节点，下标 0 为起点锚（酒店或首个 POI；即
     *                    {@link DaySplitter} 产出的每日序列形态）
     * @param matrix      通勤矩阵（须覆盖序列全部节点名，名唯一）
     * @param constraints 求解约束（时间窗/通勤上限/缓冲）
     * @return 优化后序列 + 改进量（优化前后通勤差，秒，≥0）；
     *         输入不可行（违反约束或含不可达边）时原样返回、改进量 0
     * @throws IllegalArgumentException 节点名不在矩阵中或序列内重名（契约破裂，快速失败）
     */
    public Improvement improve(List<PoiCandidate> daySequence, TimeMatrix matrix,
                               RouteConstraints constraints) {
        Objects.requireNonNull(daySequence, "daySequence");
        Objects.requireNonNull(matrix, "matrix");
        Objects.requireNonNull(constraints, "constraints");

        List<PoiCandidate> current = new ArrayList<>(daySequence);
        int n = current.size();
        if (n < 3) {
            // 少于 3 节点不存在可反转区间（锚固定），无从优化
            return new Improvement(List.copyOf(current), 0);
        }

        int[] idx = resolveIndices(current, matrix);
        long before = evaluate(current, idx, matrix, constraints);
        if (before < 0) {
            log.warn("2-opt 输入序列不可行（开放窗/日末/通勤上限/不可达边），原样返回: nodes={}", n);
            return new Improvement(List.copyOf(current), 0);
        }

        long currentTotal = before;
        int passes = 0;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            int bestI = -1;
            int bestJ = -1;
            long bestTotal = currentTotal;
            for (int i = 1; i < n - 1; i++) {
                for (int j = i + 1; j < n; j++) {
                    reverseSegment(current, idx, i, j);
                    long candidate = evaluate(current, idx, matrix, constraints);
                    reverseSegment(current, idx, i, j);
                    if (candidate >= 0 && candidate < bestTotal) {
                        bestTotal = candidate;
                        bestI = i;
                        bestJ = j;
                    }
                }
            }
            if (bestI < 0) {
                break;
            }
            reverseSegment(current, idx, bestI, bestJ);
            currentTotal = bestTotal;
            passes++;
        }

        long improvementSec = before - currentTotal;
        log.info("2-opt 优化完成: nodes={}, passes={}, improvementSec={}s ({}min -> {}min)",
                n, passes, improvementSec, Math.round(before / 60.0), Math.round(currentTotal / 60.0));
        return new Improvement(List.copyOf(current), improvementSec);
    }

    /** 优化结果：序列 + 改进量（秒，≥0） */
    public record Improvement(List<PoiCandidate> sequence, long improvementSec) {
    }

    // ==================== 内部：求值与反转 ====================

    /**
     * 整条重算：总通勤（秒）+ 硬约束校验。
     * 时间线语义与 DaySplitter 插入判定一致：锚（下标 0）入边不计通勤；
     * 无酒店锚时首点到达 = dailyStart（无缓冲），其余节点到达 = 上一节点结束
     * + 通勤 + transferBuffer。
     *
     * @return 可行返回总通勤秒数；任一约束违反或存在不可达（-1）边返回 -1
     */
    private long evaluate(List<PoiCandidate> seq, int[] idx, TimeMatrix matrix,
                          RouteConstraints c) {
        int n = seq.size();
        int endMinute = DaySplitter.minutesOf(c.dailyStart());
        int dailyEnd = DaySplitter.minutesOf(c.dailyEnd());
        long totalSec = 0;

        boolean anchorIsHotel = c.hotelName() != null
                && seq.get(0).name().equals(c.hotelName());
        if (!anchorIsHotel) {
            // 无酒店锚：首个 POI 入边通勤记 0、到达 = dailyStart（空天首点约定）
            PoiCandidate first = seq.get(0);
            int duration = DaySplitter.durationOf(first);
            if (!DaySplitter.windowCompatible(first, endMinute, duration)
                    || endMinute + duration > dailyEnd) {
                return -1;
            }
            endMinute += duration;
        }
        for (int k = 1; k < n; k++) {
            long leg = matrix.get(idx[k - 1], idx[k]);
            if (leg < 0) {
                return -1;
            }
            totalSec += leg;
            PoiCandidate node = seq.get(k);
            int duration = DaySplitter.durationOf(node);
            int arrive = endMinute + (int) Math.round(leg / 60.0) + c.transferBufferMin();
            if (!DaySplitter.windowCompatible(node, arrive, duration)
                    || arrive + duration > dailyEnd) {
                return -1;
            }
            endMinute = arrive + duration;
        }
        // 通勤上限（与 DaySplitter.commuteMinutesTotal 同口径；对严格改进的移动
        // 天然冗余——新总量 < 旧总量 ≤ 上限——防御性保留）
        if (Math.round(totalSec / 60.0) > c.maxDailyCommuteMin()) {
            return -1;
        }
        return totalSec;
    }

    /** 就地反转 [i..j] 区间（节点序列与矩阵下标同步反转） */
    private static void reverseSegment(List<PoiCandidate> seq, int[] idx, int i, int j) {
        while (i < j) {
            PoiCandidate tmpPoi = seq.set(i, seq.get(j));
            seq.set(j, tmpPoi);
            int tmpIdx = idx[i];
            idx[i] = idx[j];
            idx[j] = tmpIdx;
            i++;
            j--;
        }
    }

    /** 节点名 → 矩阵下标（缺失或重名即契约破裂，快速失败） */
    private static int[] resolveIndices(List<PoiCandidate> seq, TimeMatrix matrix) {
        int[] idx = new int[seq.size()];
        boolean[] seen = new boolean[matrix.size()];
        for (int i = 0; i < seq.size(); i++) {
            idx[i] = matrix.indexOf(seq.get(i).name());
            if (idx[i] < 0) {
                throw new IllegalArgumentException("序列节点不在矩阵中: " + seq.get(i).name());
            }
            if (seen[idx[i]]) {
                throw new IllegalArgumentException("序列内节点重名: " + seq.get(i).name());
            }
            seen[idx[i]] = true;
        }
        return idx;
    }
}
