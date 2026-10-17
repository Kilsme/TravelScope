package com.travelscope.service.route;

import com.alibaba.fastjson2.JSON;
import com.travelscope.collab.CollabFileReader;
import com.travelscope.collab.CollabFileWriter;
import com.travelscope.collab.TaskWorkspace;
import com.travelscope.service.route.dto.PoiCandidate;
import com.travelscope.service.route.dto.RouteConstraints;
import com.travelscope.service.route.dto.RouteSolveResult;
import com.travelscope.service.route.dto.TimeMatrix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S1.6 验证：路线求解三组规模压测（6/12/16 POI），性能基线落档。
 * <p>
 * 矩阵直接注入：mock {@link CommuteMatrixBuilder} 返回预构造矩阵，求解耗时只计
 * 分日 + 2-opt + 顺排 的 CPU 部分（不含 API/网络）。矩阵由固定种子的确定性
 * 伪随机生成器构造（距离相关 + 抖动）：sec(i,j) = 360 + haversineKm×240 +
 * rnd.nextInt(600)，按 i&lt;j 行序消费种子——同 fixture 必同矩阵，可复现。
 * 基线数字同步抄录至 docs/TravelScope重构计划-v4.md §七。
 * </p>
 */
class RoutePlannerScaleTest {

    private static final Logger log = LoggerFactory.getLogger(RoutePlannerScaleTest.class);

    /** 求解耗时上限（毫秒，矩阵直接注入口径；v4 计划 P1 里程碑指标 <1s） */
    private static final long SOLVE_TIME_LIMIT_MS = 1000;

    @TempDir
    Path tempDir;

    @Test
    void scale6Poi2Days() {
        runScaleFixture("/route/scale-6.json");
    }

    @Test
    void scale12Poi3Days() {
        runScaleFixture("/route/scale-12.json");
    }

    @Test
    void scale16Poi4Days() {
        runScaleFixture("/route/scale-16.json");
    }

    // ==================== 压测主流程 ====================

    private void runScaleFixture(String resource) {
        FixtureSpec spec = loadFixture(resource);
        List<PoiCandidate> pois = spec.pois().stream().map(RoutePlannerScaleTest::toPoi).toList();
        PoiCandidate hotel = new PoiCandidate(spec.hotel().name(), spec.hotel().lng(),
                spec.hotel().lat(), null, null, null, null, "hotel-anchor");

        // 确定性矩阵：[酒店, ...POI]，sec = 360 + haversine×240 + 抖动[0,600)
        List<PoiCandidate> nodes = new ArrayList<>(List.of(hotel));
        nodes.addAll(pois);
        int n = nodes.size();
        long[][] d = new long[n][n];
        Random rnd = new Random(spec.seed());
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                long sec = 360L + Math.round(haversineKm(nodes.get(i), nodes.get(j)) * 240)
                        + rnd.nextInt(600);
                d[i][j] = sec;
                d[j][i] = sec;
            }
        }
        TimeMatrix matrix = new TimeMatrix(
                nodes.stream().map(PoiCandidate::name).toList(), d);

        // 矩阵直接注入：mock 矩阵构建器（apiCalls=0，耗时零 API）
        CommuteMatrixBuilder builder = mock(CommuteMatrixBuilder.class);
        when(builder.build(any(), any(), anyBoolean()))
                .thenReturn(new MatrixBuildOutcome(matrix, nodes, List.of(), 0, true));
        RoutePlannerServiceImpl planner = new RoutePlannerServiceImpl(builder,
                new TaskWorkspace(new CollabFileWriter(tempDir.toString()), new CollabFileReader()));

        RouteConstraints c = RouteConstraints.ofDays(spec.days())
                .withHotel(spec.hotel().name(), spec.hotel().lng(), spec.hotel().lat());

        // 预热一轮（JIT + ForkJoinPool commonPool 初始化），再计时
        planner.solve(pois, c, "杭州");
        long start = System.nanoTime();
        RouteSolveResult result = planner.solve(pois, c, "杭州");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // ---- 断言：硬约束 / 记账 / 耗时 ----
        assertThat(result.days()).as("%s：天数", spec.name()).hasSize(spec.days());
        List<String> accounted = new ArrayList<>();
        int totalCommute = 0;
        for (RouteSolveResult.RouteDay day : result.days()) {
            assertThat(day.commuteMinutesTotal())
                    .as("%s day %d 通勤超上限", spec.name(), day.day())
                    .isLessThanOrEqualTo(c.maxDailyCommuteMin());
            totalCommute += day.commuteMinutesTotal();
            List<RouteSolveResult.ScheduledPoi> ps = day.pois();
            for (int k = 0; k < ps.size(); k++) {
                RouteSolveResult.ScheduledPoi p = ps.get(k);
                accounted.add(p.candidate().name());
                assertThat(min(p.startTime())).isGreaterThanOrEqualTo(9 * 60);
                assertThat(min(p.startTime()) < 13 * 60 && min(p.endTime()) > 12 * 60)
                        .as("%s day %d 第 %d 项占用午餐窗", spec.name(), day.day(), k)
                        .isFalse();
                int earliest = k == 0 ? 9 * 60 : min(ps.get(k - 1).endTime());
                assertThat(min(p.startTime()))
                        .as("%s day %d 第 %d 项时间链", spec.name(), day.day(), k)
                        .isGreaterThanOrEqualTo(earliest + p.commuteMinutesFromPrev()
                                + c.transferBufferMin());
            }
        }
        result.unplaced().forEach(u -> accounted.add(u.name()));
        assertThat(accounted).as("%s：无重复无遗漏", spec.name())
                .containsExactlyInAnyOrderElementsOf(
                        pois.stream().map(PoiCandidate::name).toList());
        assertThat(elapsedMs)
                .as("%s：求解耗时（矩阵直接注入，不含 API）应 <1s", spec.name())
                .isLessThan(SOLVE_TIME_LIMIT_MS);

        log.info("[S1.6-基线] 规模={}POI/{}天 | 求解耗时={}ms | 通勤总量={}min | unplaced={}",
                pois.size(), spec.days(), elapsedMs, totalCommute, result.unplaced().size());
    }

    // ==================== fixture 加载与工具 ====================

    private record FixtureSpec(String name, int days, long seed, HotelSpec hotel,
                               List<PoiSpec> pois) {
    }

    private record HotelSpec(String name, double lng, double lat) {
    }

    private record PoiSpec(String name, double lng, double lat, Integer durationMin,
                           String openStart, String openClose) {
    }

    private static FixtureSpec loadFixture(String resource) {
        try (InputStream in = RoutePlannerScaleTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("fixture 资源存在: %s", resource).isNotNull();
            return JSON.parseObject(in.readAllBytes(), FixtureSpec.class);
        } catch (IOException e) {
            throw new IllegalStateException("fixture 读取失败: " + resource, e);
        }
    }

    private static PoiCandidate toPoi(PoiSpec p) {
        return new PoiCandidate(p.name(), p.lng(), p.lat(), p.openStart(), p.openClose(),
                p.durationMin(), 4.5, "RAG");
    }

    private static double haversineKm(PoiCandidate a, PoiCandidate b) {
        double r = 6371.0;
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double x = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.atan2(Math.sqrt(x), Math.sqrt(1 - x));
    }

    /** "HH:mm" → 当日分钟数 */
    private static int min(String hhmm) {
        String[] parts = hhmm.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }
}
