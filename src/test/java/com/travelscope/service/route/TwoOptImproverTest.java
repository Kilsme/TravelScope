package com.travelscope.service.route;

import com.travelscope.service.route.dto.PoiCandidate;
import com.travelscope.service.route.dto.RouteConstraints;
import com.travelscope.service.route.dto.TimeMatrix;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S1.4 验证：TwoOptImprover 2-opt 局部优化。
 * 覆盖：只降不升不变式（随机 100 组 12 节点 fixture）/ 经典交叉路径解开（确定性
 * fixture 手工推演）/ 开放窗硬约束保护（改进但违约的反转被拒）/ 无酒店锚形态 /
 * 短序列与不可行输入防御 / 节点不在矩阵的契约破裂快速失败。
 */
class TwoOptImproverTest {

    private static PoiCandidate poi(String name, Integer durationMin) {
        return new PoiCandidate(name, 120.0, 30.0, null, null, durationMin, 4.5, "RAG");
    }

    /** 酒店锚节点（与 DaySplitter 构造同款形态） */
    private static PoiCandidate hotel(String name) {
        return new PoiCandidate(name, 120.0, 30.0, null, null, null, null, "hotel-anchor");
    }

    /** 序列总通勤（秒）：Σ 相邻腿（锚入边不计），测试侧独立复算 */
    private static long totalSec(List<PoiCandidate> seq, TimeMatrix m) {
        long total = 0;
        for (int k = 1; k < seq.size(); k++) {
            total += m.get(m.indexOf(seq.get(k - 1).name()), m.indexOf(seq.get(k).name()));
        }
        return total;
    }

    // ---------- 用例 1：只降不升不变式（随机 100 组 12 节点） ----------
    @Test
    void neverWorseThanInput() {
        Random rnd = new Random(20261017);
        TwoOptImprover improver = new TwoOptImprover();
        for (int iter = 0; iter < 100; iter++) {
            // 12 节点 = 酒店锚 + 11 POI；通勤 5~20min 对称随机；时长 30min；窗全空
            List<String> names = new ArrayList<>(List.of("H"));
            for (int i = 1; i <= 11; i++) {
                names.add("P" + i);
            }
            int n = names.size();
            long[][] d = new long[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    d[i][j] = d[j][i] = 300 + rnd.nextInt(901);
                }
            }
            TimeMatrix m = new TimeMatrix(names, d);

            List<PoiCandidate> pois = new ArrayList<>();
            for (int i = 1; i < n; i++) {
                pois.add(poi(names.get(i), 30));
            }
            Collections.shuffle(pois, rnd);
            List<PoiCandidate> input = new ArrayList<>(List.of(hotel("H")));
            input.addAll(pois);

            // 日窗 720min：11 点 × (30+20+10)=660 恒可行；cap 240 ≥ 11×20=220 恒不越界
            RouteConstraints c = new RouteConstraints(1, "09:00", "21:00", 240, 10,
                    "H", 120.0, 30.0, true);
            TwoOptImprover.Improvement r = improver.improve(input, m, c);

            assertThat(r.sequence())
                    .as("fixture %d：节点多重集不变", iter)
                    .containsExactlyInAnyOrderElementsOf(input);
            assertThat(r.sequence().get(0).name())
                    .as("fixture %d：酒店锚固定首位", iter)
                    .isEqualTo("H");
            long before = totalSec(input, m);
            long after = totalSec(r.sequence(), m);
            assertThat(after)
                    .as("fixture %d：总通勤只降不升（%d -> %d）", iter, before, after)
                    .isLessThanOrEqualTo(before);
            assertThat(r.improvementSec())
                    .as("fixture %d：改进量 = 前后差", iter)
                    .isEqualTo(before - after);
        }
    }

    // ---------- 用例 2：经典交叉路径解开 ----------
    @Test
    void fixesClassicCrossing() {
        // 单位圆：O=圆心（酒店锚）、A=上(0,1)、B=右(1,0)、C=下(0,-1)、D=左(-1,0)；
        // 1 单位=600s，相邻弦 √2→849s，直径（A-C、B-D）1200s。
        // 输入 O→A→C→B→D 含两条互相交叉的直径边（经典交叉路径），总通勤 3849s。
        // 手工推演：第一轮唯一最优反转 [2..3] → O→A→B→C→D（环序，无交叉，3147s），
        // 第二轮无严格改进（仅等值反转）——输出确定。
        List<String> names = List.of("O", "A", "B", "C", "D");
        long[][] d = {
                {0, 600, 600, 600, 600},
                {600, 0, 849, 1200, 849},
                {600, 849, 0, 849, 1200},
                {600, 1200, 849, 0, 849},
                {600, 849, 1200, 849, 0}};
        TimeMatrix m = new TimeMatrix(names, d);
        List<PoiCandidate> input = List.of(
                hotel("O"), poi("A", 60), poi("C", 60), poi("B", 60), poi("D", 60));
        RouteConstraints c = RouteConstraints.ofDays(1).withHotel("O", 120.0, 30.0);

        TwoOptImprover.Improvement r = new TwoOptImprover().improve(input, m, c);

        assertThat(r.sequence()).extracting(PoiCandidate::name)
                .as("交叉路径应解开为环序")
                .containsExactly("O", "A", "B", "C", "D");
        assertThat(totalSec(r.sequence(), m))
                .as("解开后总通勤 = 600+3×849")
                .isEqualTo(3147);
        assertThat(r.improvementSec())
                .as("改进量 = 3849-3147")
                .isEqualTo(702);
        List<String> out = r.sequence().stream().map(PoiCandidate::name).toList();
        for (int k = 1; k < out.size(); k++) {
            assertThat(out.get(k - 1) + "-" + out.get(k))
                    .as("交叉直径边（A-C / B-D）应被消除")
                    .isNotIn("A-C", "C-A", "B-D", "D-B");
        }
    }

    // ---------- 用例 3：开放窗硬约束保护（改进但违约的反转被拒） ----------
    @Test
    void improvingButWindowViolatingReversalRejected() {
        // [H,P1,P2,P3] 总通勤 90min；反转 [1..2] → [H,P2,P1,P3] 通勤 65min 更优，
        // 但 P2 10:30 才开门、新到达 09:15 < openStart → 拒绝；其余反转均不改进
        PoiCandidate p2 = new PoiCandidate("P2", 120.1, 30.1, "10:30", "20:00", 60, 4.5, "RAG");
        List<PoiCandidate> input = List.of(hotel("H"), poi("P1", 60), p2, poi("P3", 60));
        long[][] d = {
                {0, 1800, 300, 2400},
                {1800, 0, 1500, 2100},
                {300, 1500, 0, 2100},
                {2400, 2100, 2100, 0}};
        TimeMatrix m = new TimeMatrix(List.of("H", "P1", "P2", "P3"), d);
        RouteConstraints c = new RouteConstraints(1, "09:00", "21:00", 90, 10,
                "H", 120.0, 30.0, true);

        TwoOptImprover.Improvement r = new TwoOptImprover().improve(input, m, c);

        assertThat(r.sequence()).extracting(PoiCandidate::name)
                .as("唯一改进反转违反 P2 开放窗，应保持原序")
                .containsExactly("H", "P1", "P2", "P3");
        assertThat(r.improvementSec()).isZero();
    }

    // ---------- 用例 4：无酒店锚形态（首点入边记 0、到达无缓冲） ----------
    @Test
    void improvesWithoutHotelAnchor() {
        // [A,B,C]：A→B 30min + B→C 10min = 40min；[A,C,B] = 10+10 = 20min
        List<PoiCandidate> input = List.of(poi("A", 60), poi("B", 60), poi("C", 60));
        long[][] d = {
                {0, 1800, 600},
                {1800, 0, 600},
                {600, 600, 0}};
        TimeMatrix m = new TimeMatrix(List.of("A", "B", "C"), d);

        TwoOptImprover.Improvement r = new TwoOptImprover().improve(input, m,
                RouteConstraints.ofDays(1));

        assertThat(r.sequence()).extracting(PoiCandidate::name)
                .containsExactly("A", "C", "B");
        assertThat(r.improvementSec()).isEqualTo(1200);
    }

    // ---------- 用例 5：短序列与不可行输入防御 ----------
    @Test
    void shortOrInfeasibleInputReturnsUnchanged() {
        TwoOptImprover improver = new TwoOptImprover();
        RouteConstraints c = RouteConstraints.ofDays(1).withHotel("H", 120.0, 30.0);

        // 两节点：无可反转区间
        TimeMatrix m2 = new TimeMatrix(List.of("H", "A"), new long[][]{{0, 600}, {600, 0}});
        TwoOptImprover.Improvement r1 = improver.improve(
                List.of(hotel("H"), poi("A", 60)), m2, c);
        assertThat(r1.sequence()).hasSize(2);
        assertThat(r1.improvementSec()).isZero();

        // 不可行输入（H→A 不可达）：原样返回、不优化
        TimeMatrix m3 = new TimeMatrix(List.of("H", "A", "B"),
                new long[][]{{0, -1, 600}, {-1, 0, 600}, {600, 600, 0}});
        TwoOptImprover.Improvement r2 = improver.improve(
                List.of(hotel("H"), poi("A", 60), poi("B", 60)), m3, c);
        assertThat(r2.sequence()).extracting(PoiCandidate::name)
                .containsExactly("H", "A", "B");
        assertThat(r2.improvementSec()).isZero();
    }

    // ---------- 用例 6：节点不在矩阵 → 契约破裂快速失败 ----------
    @Test
    void unknownNodeNameFailsFast() {
        TimeMatrix m = new TimeMatrix(List.of("H", "A"), new long[][]{{0, 600}, {600, 0}});
        List<PoiCandidate> seq = List.of(hotel("H"), poi("A", 60), poi("幽灵", 60));

        assertThatThrownBy(() -> new TwoOptImprover().improve(seq, m, RouteConstraints.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("幽灵");
    }
}
