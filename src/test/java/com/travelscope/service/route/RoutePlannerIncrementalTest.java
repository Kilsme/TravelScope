package com.travelscope.service.route;

import com.travelscope.agent.tools.TransportTool;
import com.travelscope.collab.CollabFileReader;
import com.travelscope.collab.CollabFileWriter;
import com.travelscope.collab.RoutePlan;
import com.travelscope.collab.TaskWorkspace;
import com.travelscope.service.TaskResultCache;
import com.travelscope.service.route.dto.PoiCandidate;
import com.travelscope.service.route.dto.RouteConstraints;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R2 增量重组装验证：planAndWriteIncremental 的天级指纹复用。
 * 桩模式与 RoutePlannerServiceTest 一致：mock TransportTool（按坐标 haversine 生成时长）+
 * 真实 TaskResultCache 走 memoryFallback（对级缓存真实生效）+ @TempDir 工作区。
 * <p>
 * 分日语义对齐 DaySplitter 实际行为（全局最优插入贪心，空天首插零通勤）：
 * {A1,A2,B1,B2} 四点（A 近对 123s、B 近对 126s、跨对 ~1790s）的确定分日结果
 * 是 day1={A1,B2}、day2={A2,B1}（交错，非集群抱团）——测试按此设计：
 * 替换 day2 的 B1（→C，近旁点）不动 day1，验证 day1 字节级复用。
 * </p>
 */
class RoutePlannerIncrementalTest {

    @TempDir
    Path tempDir;

    private RoutePlannerServiceImpl planner;
    private RouteConstraints constraints;

    @BeforeEach
    void setUp() {
        TransportTool transportTool = mock(TransportTool.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("no redis in test"));
        TaskWorkspace workspace = new TaskWorkspace(
                new CollabFileWriter(tempDir.toString()), new CollabFileReader());
        planner = new RoutePlannerServiceImpl(
                new CommuteMatrixBuilder(transportTool, new TaskResultCache(redisTemplate), 0),
                workspace);
        // 驾车桩：时长 = 120s + 60s/km（逐对确定，远近合理）
        when(transportTool.getDrivingRoute(any(), any())).thenAnswer(inv ->
                "{\"duration\":\"" + secondsFor(inv.getArgument(0), inv.getArgument(1))
                        + "\",\"distance\":\"1000\"}");
        constraints = RouteConstraints.ofDays(2).withPreferTransit(false);
    }

    private static long secondsFor(String coordA, String coordB) {
        String[] a = coordA.split(",");
        String[] b = coordB.split(",");
        return 120 + (long) (haversineKm(Double.parseDouble(a[0]), Double.parseDouble(a[1]),
                Double.parseDouble(b[0]), Double.parseDouble(b[1])) * 60);
    }

    private static double haversineKm(double lng1, double lat1, double lng2, double lat2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double x = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.atan2(Math.sqrt(x), Math.sqrt(1 - x));
    }

    /** 240 分钟/个：2 天各容 2 个（跨对 ~30min 通勤 + 午餐后 17:40 内收班），分日结果确定 */
    private static PoiCandidate poi(String name, double lat) {
        return new PoiCandidate(name, 120.100, lat, "08:00", "19:00", 240, 4.5, "RAG");
    }

    private static List<String> names(RoutePlan.RouteDay day) {
        return day.pois().stream().map(RoutePlan.RoutePoi::name).toList();
    }

    /** 基线四点：分日结果 day1={A1,B2}、day2={A2,B1}（贪心确定，见类注释） */
    private static List<PoiCandidate> basePois() {
        return List.of(poi("A1", 30.2000), poi("A2", 30.2005),
                poi("B1", 30.4500), poi("B2", 30.4510));
    }

    @Test
    @DisplayName("换掉 day2 的 B1：day1 输入集合未变 → 字节级复用旧条目，仅 day2 重排")
    void unchangedDayReusedVerbatim() {
        RoutePlan plan1 = planner.planAndWrite("u1", "conv-inc", basePois(), constraints, "杭州");
        RoutePlan.RouteDay aDay = plan1.days().stream()
                .filter(d -> names(d).contains("A1")).findFirst().orElseThrow();
        assertThat(names(aDay)).containsExactlyInAnyOrder("A1", "B2");

        // B1 → C（近旁点，与 A1/A2/B2 的距离关系不变 → 贪心决策复现，day1 不受影响）
        List<PoiCandidate> second = List.of(poi("A1", 30.2000), poi("A2", 30.2005),
                poi("C", 30.4505), poi("B2", 30.4510));
        RoutePlannerServiceImpl.IncrementalOutcome out = planner.planAndWriteIncremental(
                "u1", "conv-inc", second, constraints, "杭州", plan1);

        assertThat(out.reusedDays()).containsExactly(aDay.day());
        assertThat(out.changedDays()).hasSize(1).doesNotContain(aDay.day());
        // 未变天逐字段一致（时段/顺序/通勤总分钟/指纹）——字节级复用
        assertThat(out.plan().days().get(aDay.day() - 1)).isEqualTo(aDay);
        assertThat(names(out.plan().days().get(aDay.day() - 1))).containsExactlyInAnyOrder("A1", "B2");
        // 变化天：B1 → C
        RoutePlan.RouteDay changedDay = out.plan().days().stream()
                .filter(d -> d.day() != aDay.day()).findFirst().orElseThrow();
        assertThat(names(changedDay)).contains("C").doesNotContain("B1");
        // 落盘与读回一致
        assertThat(new TaskWorkspace(new CollabFileWriter(tempDir.toString()), new CollabFileReader())
                .readRoutePlan("u1", "conv-inc")).contains(out.plan());
    }

    @Test
    @DisplayName("存量 route_plan（无指纹字段）按 RoutePoi 现算匹配，复用同样生效")
    void legacyPlanWithoutFingerprintStillMatches() {
        RoutePlan plan1 = planner.planAndWrite("u1", "conv-legacy", basePois(), constraints, "杭州");
        // 构造存量形态：4 参构造（fingerprint=null）
        RoutePlan legacy = new RoutePlan(plan1.days().stream()
                .map(d -> new RoutePlan.RouteDay(d.day(), d.date(), d.pois(), d.commuteMinutesTotal()))
                .toList(), plan1.solverMeta());

        List<PoiCandidate> second = List.of(poi("A1", 30.2000), poi("A2", 30.2005),
                poi("C", 30.4505), poi("B2", 30.4510));
        RoutePlannerServiceImpl.IncrementalOutcome out = planner.planAndWriteIncremental(
                "u1", "conv-legacy", second, constraints, "杭州", legacy);

        assertThat(out.reusedDays()).hasSize(1);
        RoutePlan.RouteDay reused = out.plan().days().stream()
                .filter(d -> out.reusedDays().contains(d.day())).findFirst().orElseThrow();
        assertThat(names(reused)).containsExactlyInAnyOrder("A1", "B2");
        assertThat(reused.inputFingerprint()).isNotBlank();  // 新产物已记录指纹
    }

    @Test
    @DisplayName("全部 POI 更换：零复用，退化为全量（与 planAndWrite 等价）")
    void allChangedDegeneratesToFullReplan() {
        RoutePlan plan1 = planner.planAndWrite("u1", "conv-all", basePois(), constraints, "杭州");

        List<PoiCandidate> second = List.of(poi("C1", 30.1000), poi("C2", 30.1005),
                poi("D1", 30.5500), poi("D2", 30.5510));
        RoutePlannerServiceImpl.IncrementalOutcome out = planner.planAndWriteIncremental(
                "u1", "conv-all", second, constraints, "杭州", plan1);

        assertThat(out.reusedDays()).isEmpty();
        assertThat(out.changedDays()).hasSize(2);
        assertThat(out.plan().solverMeta().unplaced()).isEmpty();
        assertThat(out.plan().days()).flatMap(d -> names(d))
                .containsExactlyInAnyOrder("C1", "C2", "D1", "D2");
    }
}
