package com.travelscope.service.route;

import com.travelscope.service.route.dto.PoiCandidate;
import com.travelscope.service.route.dto.RouteConstraints;
import com.travelscope.service.route.dto.TimeMatrix;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S1.5 验证：TimelineScheduler 时段顺排。
 * 覆盖：午餐窗顺延（区间相交）/ 端点相接不顺延（end==12:00 与 start==13:00）/
 * 关闭午餐窗 / 无酒店锚首项语义（start=dailyStart、通勤 null）/ 时间链与总通勤
 * 精确断言 / 空序列与仅酒店锚。全部 fixture 手工推演。
 */
class TimelineSchedulerTest {

    private static PoiCandidate poi(String name, Integer durationMin) {
        return new PoiCandidate(name, 120.0, 30.0, null, null, durationMin, 4.5, "RAG");
    }

    /** 酒店锚节点（与 DaySplitter 构造同款形态） */
    private static PoiCandidate hotel(String name) {
        return new PoiCandidate(name, 120.0, 30.0, null, null, null, null, "hotel-anchor");
    }

    private static final TimelineScheduler SCHEDULER = new TimelineScheduler();

    // ---------- 用例 1：跨午餐窗的 POI 顺延至 13:00 ----------
    @Test
    void lunchDefersCrossingPoi() {
        // H→A 10min：A 09:20-10:20；A→B 60min：B 原到达 11:30、原结束 12:30 跨窗 → 顺延 13:00-14:00
        List<PoiCandidate> seq = List.of(hotel("H"), poi("A", 60), poi("B", 60));
        long[][] d = {
                {0, 600, 1200},
                {600, 0, 3600},
                {1200, 3600, 0}};
        TimeMatrix m = new TimeMatrix(List.of("H", "A", "B"), d);
        RouteConstraints c = RouteConstraints.ofDays(1).withHotel("H", 120.0, 30.0);

        TimelineScheduler.DayTimeline t = SCHEDULER.schedule(seq, m, c);

        assertThat(t.pois()).hasSize(2);
        assertThat(t.pois().get(0).startTime()).isEqualTo("09:20");
        assertThat(t.pois().get(0).endTime()).isEqualTo("10:20");
        assertThat(t.pois().get(0).commuteMinutesFromPrev()).isEqualTo(10);
        assertThat(t.pois().get(1).startTime()).as("跨窗 POI 应顺延至 13:00").isEqualTo("13:00");
        assertThat(t.pois().get(1).endTime()).isEqualTo("14:00");
        assertThat(t.pois().get(1).commuteMinutesFromPrev()).isEqualTo(60);
        assertThat(t.commuteMinutesTotal()).isEqualTo(70);
    }

    // ---------- 用例 2：端点相接不算相交（end==12:00 与 start==13:00 均不顺延） ----------
    @Test
    void lunchBoundaryTouchingNotDeferred() {
        // A 09:20-10:20；B 11:00-12:00（结束恰在窗始，不顺延）；C 13:00-14:00（开始恰在窗末，不顺延）
        List<PoiCandidate> seq = List.of(hotel("H"), poi("A", 60), poi("B", 60), poi("C", 60));
        long[][] d = {
                {0, 600, 3600, 3600},
                {600, 0, 1800, 3600},
                {3600, 1800, 0, 3000},
                {3600, 3600, 3000, 0}};
        TimeMatrix m = new TimeMatrix(List.of("H", "A", "B", "C"), d);
        RouteConstraints c = RouteConstraints.ofDays(1).withHotel("H", 120.0, 30.0);

        TimelineScheduler.DayTimeline t = SCHEDULER.schedule(seq, m, c);

        assertThat(t.pois().get(1).startTime()).as("结束恰为 12:00 不顺延").isEqualTo("11:00");
        assertThat(t.pois().get(1).endTime()).isEqualTo("12:00");
        assertThat(t.pois().get(2).startTime()).as("开始恰为 13:00 不顺延").isEqualTo("13:00");
        assertThat(t.pois().get(2).endTime()).isEqualTo("14:00");
    }

    // ---------- 用例 3：关闭午餐窗（null,null）不预留 ----------
    @Test
    void lunchDisabledByNullKeepsOriginalTimes() {
        List<PoiCandidate> seq = List.of(hotel("H"), poi("A", 60), poi("B", 60));
        long[][] d = {
                {0, 600, 1200},
                {600, 0, 3600},
                {1200, 3600, 0}};
        TimeMatrix m = new TimeMatrix(List.of("H", "A", "B"), d);
        RouteConstraints c = RouteConstraints.ofDays(1).withHotel("H", 120.0, 30.0)
                .withLunchWindow(null, null);

        TimelineScheduler.DayTimeline t = SCHEDULER.schedule(seq, m, c);

        assertThat(t.pois().get(1).startTime()).as("无午餐窗则保持 11:30").isEqualTo("11:30");
        assertThat(t.pois().get(1).endTime()).isEqualTo("12:30");
    }

    // ---------- 用例 4：无酒店锚——首项 start=dailyStart、前序通勤为 null ----------
    @Test
    void noHotelFirstPoiStartsAtDailyStart() {
        // A 09:00-10:00（无通勤无缓冲）；B 10:20-11:20（通勤 10min + 缓冲 10min）
        List<PoiCandidate> seq = List.of(poi("A", 60), poi("B", 60));
        long[][] d = {
                {0, 600},
                {600, 0}};
        TimeMatrix m = new TimeMatrix(List.of("A", "B"), d);

        TimelineScheduler.DayTimeline t = SCHEDULER.schedule(seq, m, RouteConstraints.ofDays(1));

        assertThat(t.pois().get(0).startTime()).isEqualTo("09:00");
        assertThat(t.pois().get(0).endTime()).isEqualTo("10:00");
        assertThat(t.pois().get(0).transitFromPrev()).isNull();
        assertThat(t.pois().get(0).commuteMinutesFromPrev()).isNull();
        assertThat(t.pois().get(1).startTime()).isEqualTo("10:20");
        assertThat(t.pois().get(1).endTime()).isEqualTo("11:20");
        assertThat(t.pois().get(1).commuteMinutesFromPrev()).isEqualTo(10);
        assertThat(t.pois().get(1).transitFromPrev()).isEqualTo("驾车");
        assertThat(t.commuteMinutesTotal()).isEqualTo(10);
    }

    // ---------- 用例 5：空序列与仅酒店锚 ----------
    @Test
    void emptyOrHotelOnlySequenceYieldsNoItems() {
        TimeMatrix m = new TimeMatrix(List.of("H"), new long[][]{{0}});
        RouteConstraints c = RouteConstraints.ofDays(1).withHotel("H", 120.0, 30.0);

        assertThat(SCHEDULER.schedule(List.of(), m, c).pois()).isEmpty();
        TimelineScheduler.DayTimeline hotelOnly =
                SCHEDULER.schedule(List.of(hotel("H")), m, c);
        assertThat(hotelOnly.pois()).isEmpty();
        assertThat(hotelOnly.commuteMinutesTotal()).isZero();
    }
}
