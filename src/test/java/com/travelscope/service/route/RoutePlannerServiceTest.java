package com.travelscope.service.route;

import com.alibaba.fastjson2.JSON;
import com.travelscope.agent.tools.TransportTool;
import com.travelscope.collab.CollabFileReader;
import com.travelscope.collab.CollabFileWriter;
import com.travelscope.collab.CollabSchemaValidator;
import com.travelscope.collab.RoutePlan;
import com.travelscope.collab.TaskWorkspace;
import com.travelscope.collab.TransportSelection;
import com.travelscope.service.TaskResultCache;
import com.travelscope.service.route.dto.OpenWindowPolicy;
import com.travelscope.service.route.dto.PoiCandidate;
import com.travelscope.service.route.dto.RouteConstraints;
import com.travelscope.service.route.dto.RouteSolveResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S1.5 验证：RoutePlannerServiceImpl 全链（矩阵→分日→2-opt→顺排→落盘）。
 * 覆盖：12 POI 端到端（卡片指定用例，输出过 CollabSchemaValidator + 硬约束断言）/
 * 无坐标 POI 记入 unplaced / 驾车档免 city / 公交档缺 city 快速失败 / solve 直连。
 * 桩模式与 CommuteMatrixBuilderTest 一致：mock TransportTool（按坐标距离生成时长）+
 * 真实 TaskResultCache 走 memoryFallback + @TempDir 工作区。
 */
class RoutePlannerServiceTest {

    @TempDir
    Path tempDir;

    private TransportTool transportTool;
    private RoutePlannerServiceImpl planner;

    @BeforeEach
    void setUp() {
        transportTool = mock(TransportTool.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("no redis in test"));
        TaskWorkspace workspace = new TaskWorkspace(
                new CollabFileWriter(tempDir.toString()), new CollabFileReader());
        planner = new RoutePlannerServiceImpl(
                new CommuteMatrixBuilder(transportTool, new TaskResultCache(redisTemplate), 0),
                workspace);
        // 距离相关桩：按两点坐标 haversine 生成时长——每对不同、远近合理
        when(transportTool.getDrivingRoute(any(), any())).thenAnswer(inv ->
                drivingJson(distanceSeconds(inv.getArgument(0), inv.getArgument(1), 150, 60)));
        when(transportTool.getTransitRoute(any(), any(), any())).thenAnswer(inv ->
                transitJson(distanceSeconds(inv.getArgument(0), inv.getArgument(1), 90, 420)));
    }

    @Test
    void selectedHotelAppearsAsEveryDayStartAndEndNode() {
        RoutePlan plan = planner.planAndWrite("u1", "conv-d4-route",
                List.of(new PoiCandidate("西湖", 120.12, 30.22, null, null, 60, 4.8, "fixture")),
                RouteConstraints.ofDays(2).withHotel("主酒店", 120.10, 30.20)
                        .withPreferTransit(false), null);

        assertThat(plan.days()).allSatisfy(day -> {
            assertThat(day.nodes().getFirst().type()).isEqualTo(RoutePlan.NodeType.HOTEL_CHECKIN);
            assertThat(day.nodes().getLast().type()).isEqualTo(RoutePlan.NodeType.HOTEL_CHECKOUT);
            assertThat(day.nodes().getFirst().name()).isEqualTo("主酒店");
            assertThat(day.nodes().getLast().name()).isEqualTo("主酒店");
        });
    }

    @Test
    void selectedTransportCreatesFixedBoundariesAndUnplacesConflict() {
        PoiCandidate tooLong = new PoiCandidate("超长景点", 120.1, 30.2,
                null, null, 240, 4.5, "fixture");
        var outbound = transport("G1", "2026-10-25T09:00", "2026-10-25T10:00");
        var inbound = transport("G2", "2026-10-25T16:00", "2026-10-25T17:00");
        var selection = new TransportSelection(
                new TransportSelection.DirectionSelection(outbound, List.of()),
                new TransportSelection.DirectionSelection(inbound, List.of()));

        RoutePlan plan = planner.planAndWriteWithTransport("u1", "conv-d3", List.of(tooLong),
                RouteConstraints.ofDays(1).withPreferTransit(false), null, selection,
                30, 30, 20, 30, 60);

        assertThat(plan.days().getFirst().nodes()).extracting(RoutePlan.RouteNode::type)
                .containsExactly(RoutePlan.NodeType.INTERCITY_TRANSPORT,
                        RoutePlan.NodeType.INTERCITY_TRANSPORT);
        assertThat(plan.solverMeta().unplaced()).singleElement().satisfies(item ->
                assertThat(item.reason()).isEqualTo("TRANSPORT_WINDOW_CONFLICT"));
    }

    private static TransportSelection.TransportOption transport(String no, String dep, String arr) {
        return new TransportSelection.TransportOption(no, "TRAIN", BigDecimal.valueOf(100),
                LocalDateTime.parse(dep), LocalDateTime.parse(arr), 60, 0, true, "飞猪", null);
    }

    @Test
    void fixedSunsetReordersConv102JiuxiBeforeEveningActivity() {
        TaskWorkspace workspace = new TaskWorkspace(
                new CollabFileWriter(tempDir.toString()), new CollabFileReader());
        RoutePlannerServiceImpl fixedSunPlanner = new RoutePlannerServiceImpl(
                new CommuteMatrixBuilder(transportTool,
                        new TaskResultCache(mock(StringRedisTemplate.class)), 0),
                workspace, city -> java.time.LocalTime.of(17, 45));
        var daylight = new OpenWindowPolicy(OpenWindowPolicy.OpenStatus.DAYLIGHT_ONLY,
                List.of(new OpenWindowPolicy.TimeWindow("08:00", "18:00")), "conv-102", java.time.Instant.EPOCH);
        var night = new OpenWindowPolicy(OpenWindowPolicy.OpenStatus.UNKNOWN,
                List.of(), "conv-102", java.time.Instant.EPOCH);
        List<PoiCandidate> input = List.of(
                new PoiCandidate("西湖音乐喷泉夜景", 120.161, 30.252, null, null,
                        60, 4.8, "fixture", night, null),
                new PoiCandidate("九溪烟树", 120.11335, 30.202395, "08:00", "18:00",
                        120, 4.8, "fixture", daylight, null));

        RouteSolveResult result = fixedSunPlanner.solve(input,
                new RouteConstraints(1, "15:00", "21:00", null, null, 90, 10,
                        null, null, null, false), "杭州");

        assertThat(result.days().getFirst().pois()).extracting(p -> p.candidate().name())
                .containsExactly("九溪烟树", "西湖音乐喷泉夜景");
        assertThat(result.days().getFirst().pois().getFirst().endTime()).isLessThanOrEqualTo("17:45");
        assertThat(result.days().getFirst().pois().get(1).startTime()).isGreaterThanOrEqualTo("18:00");
    }

    // ---------- 用例 1（卡片指定）：12 POI 全链端到端 ----------
    @Test
    void endToEndProducesValidRoutePlanJson() {
        RouteConstraints c = RouteConstraints.ofDays(2).withHotel("西湖文化酒店", 120.15, 30.25);

        RoutePlan plan = planner.planAndWrite("u1", "conv-e2e", hangzhouPois(), c, "杭州");

        // 输出 DTO 过 CollabSchemaValidator（与 Writer 落盘同一条校验管线）
        CollabSchemaValidator.validate(JSON.toJSONString(plan), RoutePlan.class);

        // 落盘存在且读回一致（往返）
        assertThat(Files.exists(tempDir.resolve("u1").resolve("tasks")
                .resolve("conv-e2e").resolve("route_plan.json"))).isTrue();
        assertThat(new TaskWorkspace(new CollabFileWriter(tempDir.toString()), new CollabFileReader())
                .readRoutePlan("u1", "conv-e2e")).contains(plan);

        // 硬约束：天数 / 单日通勤上限 / 时间链 / 午餐窗 / 日内边界
        assertThat(plan.days()).hasSize(2);
        assertThat(plan.days()).extracting(RoutePlan.RouteDay::day).containsExactly(1, 2);
        for (RoutePlan.RouteDay day : plan.days()) {
            assertThat(day.commuteMinutesTotal())
                    .as("day %d 通勤不得超上限", day.day())
                    .isLessThanOrEqualTo(c.maxDailyCommuteMin());
            List<RoutePlan.RoutePoi> ps = day.pois();
            for (int k = 0; k < ps.size(); k++) {
                RoutePlan.RoutePoi p = ps.get(k);
                assertThat(min(p.startTime())).isGreaterThanOrEqualTo(9 * 60);
                // 午餐窗硬规则：任何 POI 区间不得与 (12:00, 13:00) 相交
                assertThat(min(p.startTime()) < 13 * 60 && min(p.endTime()) > 12 * 60)
                        .as("day %d 第 %d 项不得占用午餐窗", day.day(), k)
                        .isFalse();
                // 酒店锚定：每项都有前序通勤；时间链 start ≥ 前项结束 + 通勤 + 缓冲
                assertThat(p.commuteMinutesFromPrev()).isNotNull();
                assertThat(p.transitFromPrev()).isNotNull();
                int earliest = k == 0
                        ? 9 * 60
                        : min(ps.get(k - 1).endTime());
                assertThat(min(p.startTime()))
                        .as("day %d 第 %d 项时间链", day.day(), k)
                        .isGreaterThanOrEqualTo(earliest + p.commuteMinutesFromPrev()
                                + c.transferBufferMin());
                // dailyEnd 仅可被午餐顺延突破（文档化优先级），不得跨天
                assertThat(min(p.endTime())).isLessThanOrEqualTo(23 * 60 + 59);
            }
        }

        // 记账不变式：placed + unplaced 恰为 12 个输入名、各一次
        List<String> accounted = new java.util.ArrayList<>(
                plan.days().stream().flatMap(d -> d.pois().stream())
                        .map(RoutePlan.RoutePoi::name).toList());
        plan.solverMeta().unplaced().forEach(u -> accounted.add(u.name()));
        assertThat(accounted).containsExactlyInAnyOrderElementsOf(
                hangzhouPois().stream().map(PoiCandidate::name).toList());

        assertThat(plan.solverMeta().algorithm()).isEqualTo("NN+2opt");
        assertThat(plan.solverMeta().matrixSource()).isEqualTo("amap-transit");
        assertThat(plan.solverMeta().durationMs()).isGreaterThanOrEqualTo(0);
    }

    // ---------- 用例 2：无坐标 POI（geocode 补齐失败）→ unplaced=MATRIX_MISSING ----------
    @Test
    void coordinatelessPoiGoesUnplaced() {
        when(transportTool.geocode("未知遗迹"))
                .thenReturn("{\"error\":\"高德 API 请求失败，HTTP 500\"}");
        List<PoiCandidate> pois = List.of(
                poi("断桥残雪", 120.1503, 30.2594, 60),
                poi("浙江省博物馆", 120.1450, 30.2620, 90),
                new PoiCandidate("未知遗迹", null, null, null, null, 90, 4.0, "RAG"));
        RouteConstraints c = RouteConstraints.ofDays(1).withHotel("西湖文化酒店", 120.15, 30.25);

        RoutePlan plan = planner.planAndWrite("u2", "conv-s2", pois, c, "杭州");

        assertThat(plan.solverMeta().unplaced())
                .anySatisfy(u -> {
                    assertThat(u.name()).isEqualTo("未知遗迹");
                    assertThat(u.reason()).isEqualTo("MATRIX_MISSING");
                });
        List<String> placed = plan.days().stream()
                .flatMap(d -> d.pois().stream()).map(RoutePlan.RoutePoi::name).toList();
        assertThat(placed).doesNotContain("未知遗迹");
    }

    // ---------- 用例 3：驾车档免 city，matrixSource=amap-driving ----------
    @Test
    void drivingModeAllowsNullCityAndReportsSource() {
        List<PoiCandidate> pois = List.of(
                poi("断桥残雪", 120.1503, 30.2594, 60),
                poi("浙江省博物馆", 120.1450, 30.2620, 90),
                poi("河坊街", 120.1700, 30.2400, 120));
        RouteConstraints c = RouteConstraints.ofDays(1)
                .withHotel("西湖文化酒店", 120.15, 30.25)
                .withPreferTransit(false);

        RouteSolveResult result = planner.solve(pois, c, null);

        assertThat(result.solverMeta().matrixSource()).isEqualTo("amap-driving");
        verify(transportTool, never()).getTransitRoute(any(), any(), any());
    }

    // ---------- 用例 4：公交档缺 city → 快速失败 ----------
    @Test
    void transitModeRequiresCity() {
        assertThatThrownBy(() -> planner.solve(hangzhouPois(),
                RouteConstraints.ofDays(2).withHotel("西湖文化酒店", 120.15, 30.25), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("city");
    }

    // ---------- 用例 5：solve 直连（不经落盘）同不变式 ----------
    @Test
    void solveOnlyReturnsRouteSolveResult() {
        RouteConstraints c = RouteConstraints.ofDays(2).withHotel("西湖文化酒店", 120.15, 30.25);

        RouteSolveResult result = planner.solve(hangzhouPois(), c, "杭州");

        assertThat(result.days()).hasSize(2);
        for (RouteSolveResult.RouteDay day : result.days()) {
            assertThat(day.commuteMinutesTotal()).isLessThanOrEqualTo(90);
            assertThat(day.pois()).allSatisfy(p -> {
                assertThat(p.startTime()).isNotNull();
                assertThat(p.endTime()).isNotNull();
            });
        }
        assertThat(result.solverMeta().algorithm()).isEqualTo("NN+2opt");
        List<String> accounted = new java.util.ArrayList<>(result.days().stream()
                .flatMap(d -> d.pois().stream()).map(p -> p.candidate().name()).toList());
        result.unplaced().forEach(u -> accounted.add(u.name()));
        assertThat(accounted).containsExactlyInAnyOrderElementsOf(
                hangzhouPois().stream().map(PoiCandidate::name).toList());
    }

    // ==================== fixture 与桩 ====================

    /** 杭州西湖周边 12 POI（真实坐标，时长 60~120min，部分开放窗） */
    private static List<PoiCandidate> hangzhouPois() {
        return List.of(
                poi("西湖", 120.1449, 30.2436, 120),
                windowedPoi("雷峰塔", 120.1486, 30.2312, 120, "08:00", "17:30"),
                windowedPoi("灵隐寺", 120.0962, 30.2435, 120, "07:00", "18:00"),
                poi("断桥残雪", 120.1503, 30.2594, 60),
                poi("苏堤春晓", 120.1350, 30.2400, 90),
                windowedPoi("河坊街", 120.1700, 30.2400, 120, "09:00", "21:00"),
                windowedPoi("西溪湿地", 120.0600, 30.2600, 120, "08:00", "17:30"),
                poi("京杭大运河", 120.1900, 30.2900, 120),
                windowedPoi("浙江省博物馆", 120.1450, 30.2620, 90, "09:00", "17:00"),
                windowedPoi("太子湾公园", 120.1400, 30.2200, 90, "08:00", "17:00"),
                poi("龙井村", 120.0900, 30.2200, 120),
                poi("九溪烟树", 120.1100, 30.2000, 120));
    }

    private static PoiCandidate poi(String name, double lng, double lat, int durationMin) {
        return new PoiCandidate(name, lng, lat, null, null, durationMin, 4.5, "RAG");
    }

    private static PoiCandidate windowedPoi(String name, double lng, double lat, int durationMin,
                                            String openStart, String openClose) {
        return new PoiCandidate(name, lng, lat, openStart, openClose, durationMin, 4.5, "RAG");
    }

    private static String drivingJson(long seconds) {
        return "{\"duration\":\"" + seconds + "\",\"distance\":\"1500\"}";
    }

    private static String transitJson(long seconds) {
        return "{\"count\":1,\"routes\":[{\"duration\":\"" + seconds + "\",\"distance\":\"8000\"}]}";
    }

    /** "lng,lat" 两点按 haversine 生成距离相关时长（秒） */
    private static long distanceSeconds(String from, String to, double secPerKm, long baseSec) {
        String[] f = from.split(",");
        String[] t = to.split(",");
        return Math.round(haversineKm(Double.parseDouble(f[0]), Double.parseDouble(f[1]),
                Double.parseDouble(t[0]), Double.parseDouble(t[1])) * secPerKm + baseSec);
    }

    private static double haversineKm(double lng1, double lat1, double lng2, double lat2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** "HH:mm" → 当日分钟数 */
    private static int min(String hhmm) {
        String[] parts = hhmm.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }
}
