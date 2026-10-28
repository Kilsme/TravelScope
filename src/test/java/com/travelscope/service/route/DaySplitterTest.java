package com.travelscope.service.route;

import com.travelscope.service.route.dto.PoiCandidate;
import com.travelscope.service.route.dto.RouteConstraints;
import com.travelscope.service.route.dto.RouteSolveResult;
import com.travelscope.service.route.dto.TimeMatrix;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S1.3 验证：DaySplitter 最近邻分日构造。
 * 覆盖：就近分日 / 通勤溢出 / 开放窗硬拒 / 矩阵缺失硬拒 / 多日均衡 /
 * 开放窗兼容落位 / 酒店锚定。不变式：每日通勤 ≤ 上限；POI 恰好出现一次。
 */
class DaySplitterTest {

    private static PoiCandidate poi(String name, Integer durationMin) {
        return new PoiCandidate(name, 120.0, 30.0, null, null, durationMin, 4.5, "RAG");
    }

    /** n 节点单位矩阵：对角线 0，其余按给定时长（秒） */
    private static TimeMatrix uniformMatrix(List<String> names, long seconds) {
        int n = names.size();
        long[][] d = new long[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                d[i][j] = (i == j) ? 0 : seconds;
            }
        }
        return new TimeMatrix(names, d);
    }

    @Test
    void fixedMatrixBalancesEffectiveVisitMinutesAcrossDays() {
        List<PoiCandidate> pois = List.of(poi("A", 180), poi("B", 180), poi("C", 180),
                poi("D", 180), poi("E", 180), poi("F", 180));
        var outcome = new DaySplitter().split(pois,
                uniformMatrix(List.of("A", "B", "C", "D", "E", "F"), 5 * 60),
                RouteConstraints.ofDays(3).withPace("MODERATE"));

        assertThat(outcome.days()).extracting(d -> d.effectiveVisitMinutes())
                .containsExactly(360, 360, 360);
        assertThat(outcome.days().get(0).endMinute()).isLessThanOrEqualTo(19 * 60);
        assertThat(outcome.days().get(2).effectiveVisitMinutes()).isGreaterThanOrEqualTo(300);
    }

    @Test
    void lunchAndDinnerWindowsAreReserved() {
        List<PoiCandidate> pois = List.of(poi("上午", 150), poi("下午", 240), poi("晚上", 120));
        var outcome = new DaySplitter().split(pois,
                uniformMatrix(List.of("上午", "下午", "晚上"), 0),
                RouteConstraints.ofDays(1).withPace("INTENSIVE"));

        assertThat(outcome.days().getFirst().mealBreakMinutes()).isGreaterThanOrEqualTo(60);
        assertThat(outcome.days().getFirst().endMinute()).isEqualTo(15 * 60 + 30);
    }

    @Test
    void paceProfilesUseDifferentDailyVisitCaps() {
        assertThat(RouteConstraints.ofDays(1).withPace("RELAXED").maxEffectiveVisitMinutes()).isEqualTo(360);
        assertThat(RouteConstraints.ofDays(1).withPace("MODERATE").maxEffectiveVisitMinutes()).isEqualTo(480);
        assertThat(RouteConstraints.ofDays(1).withPace("INTENSIVE").maxEffectiveVisitMinutes()).isEqualTo(600);
    }

    @Test
    void transportBoundariesConstrainFirstAndLastDayWithoutShrinkingBuffers() {
        List<PoiCandidate> pois = List.of(poi("首日景点", 120), poi("末日景点", 120));
        TimeMatrix matrix = uniformMatrix(List.of("首日景点", "末日景点"), 10 * 60);
        RouteConstraints constraints = RouteConstraints.ofDays(2)
                .withTransportWindows("12:00", "15:00");

        var outcome = new DaySplitter().split(pois, matrix, constraints);

        assertThat(outcome.days().get(0).endMinute()).isGreaterThanOrEqualTo(14 * 60);
        assertThat(outcome.days().get(1).endMinute()).isLessThanOrEqualTo(15 * 60);
    }

    @Test
    void poiThatOnlyFitsNormalWindowUsesTransportConflictReason() {
        PoiCandidate longPoi = poi("长时景点", 240);
        RouteConstraints constraints = RouteConstraints.ofDays(1)
                .withTransportWindows("12:00", "15:00");

        var outcome = new DaySplitter().split(List.of(longPoi),
                uniformMatrix(List.of("长时景点"), 0), constraints);

        assertThat(outcome.unplaced()).singleElement().satisfies(item ->
                assertThat(item.reason()).isEqualTo(com.travelscope.collab.UnplacedReason.TRANSPORT_WINDOW_CONFLICT));
    }

    // ---------- 用例 1：就近分日 + 不变式 ----------
    @Test
    void nearestNeighborSplitRespectsCommuteCap() {
        // 3 POI、2 天；通勤 20min/段，上限 90min
        List<PoiCandidate> pois = List.of(poi("A", 60), poi("B", 60), poi("C", 60));
        TimeMatrix m = uniformMatrix(List.of("A", "B", "C"), 20 * 60);
        var outcome = new DaySplitter().split(pois, m, RouteConstraints.ofDays(2));

        assertThat(outcome.days()).hasSize(2);
        for (var day : outcome.days()) {
            assertThat(day.commuteMinutesTotal())
                    .as("第 %d 天通勤不得超上限", day.dayNumber())
                    .isLessThanOrEqualTo(90);
        }
        // 不变式：3 个 POI 全部落位且无重复
        List<String> placed = outcome.days().stream()
                .flatMap(d -> d.sequence().stream())
                .map(PoiCandidate::name).toList();
        assertThat(placed).containsExactlyInAnyOrder("A", "B", "C");
        assertThat(outcome.unplaced()).isEmpty();
    }

    // ---------- 用例 2：孤立远点通勤溢出 ----------
    @Test
    void isolatedFarPoiFallsToCommuteOverflow() {
        // 近点 A/B 通勤 20min；远点 C 到任何点 100min——1 天上限 90min 装不下 C
        List<PoiCandidate> pois = List.of(poi("A", 60), poi("B", 60), poi("C", 60));
        long[][] d = {
                {0, 20 * 60, 100 * 60},
                {20 * 60, 0, 100 * 60},
                {100 * 60, 100 * 60, 0}};
        TimeMatrix m = new TimeMatrix(List.of("A", "B", "C"), d);
        var outcome = new DaySplitter().split(pois, m, RouteConstraints.ofDays(1));

        assertThat(outcome.unplaced()).extracting(RouteSolveResult.UnplacedPoi::name)
                .containsExactly("C");
        assertThat(outcome.unplaced().get(0).reason())
                .isEqualTo(com.travelscope.collab.UnplacedReason.COMMUTE_OVERFLOW);
        // A、B 正常落位
        List<String> placed = outcome.days().get(0).sequence().stream()
                .map(PoiCandidate::name).toList();
        assertThat(placed).containsExactlyInAnyOrder("A", "B");
    }

    // ---------- 用例 3：游玩时长超开窗 → TIME_WINDOW_MISS ----------
    @Test
    void durationExceedingOpenWindowIsHardRejected() {
        PoiCandidate tight = new PoiCandidate("限时景点", 120.0, 30.0,
                "09:00", "10:00", 120, 4.5, "RAG");   // 开窗 60min 但要玩 120min
        var outcome = new DaySplitter().split(List.of(tight),
                uniformMatrix(List.of("限时景点"), 0), RouteConstraints.ofDays(1));

        assertThat(outcome.unplaced()).hasSize(1);
        assertThat(outcome.unplaced().get(0).reason())
                .isEqualTo(com.travelscope.collab.UnplacedReason.OPENING_WINDOW_CONFLICT);
    }

    // ---------- 用例 4：矩阵全 -1 → 孤岛点独处一天（不可达语义），多天时落 MATRIX_MISSING ----------
    @Test
    void unreachablePoiCannotJoinReachableDay() {
        // 无酒店锚：空天首 POI 通勤记 0（特性）——2 天时 A、孤岛各成一天（孤岛通勤 0 合法落位）
        List<PoiCandidate> pois = List.of(poi("A", 60), poi("孤岛", 60));
        long[][] d = {{0, -1}, {-1, 0}};
        TimeMatrix m = new TimeMatrix(List.of("A", "孤岛"), d);
        var outcome = new DaySplitter().split(pois, m, RouteConstraints.ofDays(2));

        // 孤岛独处一天：通勤 0，不与可达点同天（同天需查矩阵 -1，被拒）
        boolean islandDayFound = false;
        for (var day : outcome.days()) {
            List<String> names = day.sequence().stream().map(PoiCandidate::name).toList();
            if (names.contains("孤岛")) {
                assertThat(names).as("孤岛所在天不得含可达点 A").doesNotContain("A");
                islandDayFound = true;
            }
        }
        assertThat(islandDayFound).as("孤岛应落位到某一天").isTrue();
        assertThat(outcome.unplaced()).isEmpty();

        // 单天时：孤岛无空天可入，与 A 通勤 -1 → MATRIX_MISSING
        var single = new DaySplitter().split(pois, m, RouteConstraints.ofDays(1));
        assertThat(single.unplaced()).extracting(RouteSolveResult.UnplacedPoi::name)
                .containsExactly("孤岛");
        assertThat(single.unplaced().get(0).reason())
                .isEqualTo(com.travelscope.collab.UnplacedReason.MISSING_COORDINATE);
    }

    // ---------- 用例 5：多日均衡（余量降序键生效） ----------
    @Test
    void multiDayBalancingBySlackPreference() {
        // 4 个通勤 40min 的点、2 天、上限 90min：每天最多装 2 段（40*2=80≤90），
        // 若全塞同一天会爆（160>90）——均衡后应 2/2 或相近分布
        List<PoiCandidate> pois = List.of(poi("A", 30), poi("B", 30), poi("C", 30), poi("D", 30));
        TimeMatrix m = uniformMatrix(List.of("A", "B", "C", "D"), 40 * 60);
        var outcome = new DaySplitter().split(pois, m, RouteConstraints.ofDays(2));

        assertThat(outcome.unplaced()).isEmpty();
        int day1Size = outcome.days().get(0).sequence().size();
        int day2Size = outcome.days().get(1).sequence().size();
        assertThat(Math.abs(day1Size - day2Size))
                .as("余量降序应使两天分布均衡（而非 3/1 或 4/0）")
                .isLessThanOrEqualTo(1);
        // 不变式：4 点各出现一次
        assertThat(day1Size + day2Size).isEqualTo(4);
    }

    // ---------- 用例 6：开放窗兼容（早开窗景点被排到上午可达位） ----------
    @Test
    void timeWindowCompatibilityRespected() {
        // 早市景点 08:00-09:00 开放（玩 30min）；普通景点全天
        PoiCandidate morning = new PoiCandidate("早市", 120.0, 30.0,
                "08:00", "09:00", 30, 4.5, "RAG");
        PoiCandidate normal = poi("常规景点", 60);
        // 通勤 10min：dailyStart 09:00 出发 → 09:10 到早市（09:10+30=09:40 > 09:00 关窗，不兼容）
        TimeMatrix m = uniformMatrix(List.of("早市", "常规景点"), 10 * 60);
        var outcome = new DaySplitter().split(List.of(morning, normal), m,
                new RouteConstraints(1, "09:00", "21:00", null, null, 90, 10, null, null, null, true));

        // 早市 09:10 到达已错过关窗 → TIME_WINDOW_MISS（本轮无兼容天）
        assertThat(outcome.unplaced()).extracting(RouteSolveResult.UnplacedPoi::name)
                .containsExactly("早市");
        assertThat(outcome.days().get(0).sequence()).extracting(PoiCandidate::name)
                .containsExactly("常规景点");
    }

    // ---------- 用例 7：酒店锚定（每日从酒店出发，通勤从酒店起算） ----------
    @Test
    void hotelAnchorsEachDaySequence() {
        List<PoiCandidate> pois = List.of(poi("A", 60), poi("B", 60));
        // 节点表：[酒店, A, B]；酒店→A 15min，酒店→B 30min，A↔B 20min
        long[][] d = {
                {0, 15 * 60, 30 * 60},
                {15 * 60, 0, 20 * 60},
                {30 * 60, 20 * 60, 0}};
        TimeMatrix m = new TimeMatrix(List.of("西湖某酒店", "A", "B"), d);
        RouteConstraints c = RouteConstraints.ofDays(1)
                .withHotel("西湖某酒店", 120.1, 30.2);
        var outcome = new DaySplitter().split(pois, m, c);

        assertThat(outcome.unplaced()).isEmpty();
        var day = outcome.days().get(0);
        assertThat(day.sequence().get(0).name())
                .as("每日序列首节点应为酒店锚")
                .isEqualTo("西湖某酒店");
        assertThat(day.sequence()).extracting(PoiCandidate::name)
                .containsExactly("西湖某酒店", "A", "B");
        // 通勤总量 = 酒店→A(15) + A→B(20) = 35min（最近邻从酒店出发选 A 先）
        assertThat(day.commuteMinutesTotal()).isEqualTo(35);
        assertThat(day.commuteMinutesTotal()).isLessThanOrEqualTo(90);
    }
}
