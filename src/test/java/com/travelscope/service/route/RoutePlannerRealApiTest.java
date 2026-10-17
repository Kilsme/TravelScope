package com.travelscope.service.route;

import com.travelscope.agent.tools.TransportTool;
import com.travelscope.collab.CollabFileReader;
import com.travelscope.collab.CollabFileWriter;
import com.travelscope.collab.RoutePlan;
import com.travelscope.collab.TaskWorkspace;
import com.travelscope.config.AppProperties;
import com.travelscope.service.TaskResultCache;
import com.travelscope.service.route.dto.PoiCandidate;
import com.travelscope.service.route.dto.RouteConstraints;
import com.travelscope.service.route.dto.TimeMatrix;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S1.9 验证：P1 真实 API 冒烟——RoutePlanner 全链接真实高德跑通。
 * <p>
 * 门控与既有 real-api 测试同款：{@code AMAP_WEB_API_KEY} 环境变量存在才启用
 * （默认关闭、带 key 手动跑），@Tag("real-api") 供 -Dgroups 选用。手工装配
 * （不经 Spring）：AppProperties → TransportTool → CommuteMatrixBuilder →
 * RoutePlannerServiceImpl。
 * </p>
 * <p>
 * 场景：杭州 6 个知名 POI + 酒店锚，①真实高德构建通勤矩阵，断言全节点对可达
 * （无 -1）；②全链 planAndWrite（矩阵缓存命中零二次 API）输出 route_plan.json，
 * 人工可读检查点（每天每项时段/交通/通勤）打印到日志。
 * </p>
 */
@Tag("real-api")
@EnabledIfEnvironmentVariable(named = "AMAP_WEB_API_KEY", matches = ".+")
class RoutePlannerRealApiTest {

    private static final Logger log = LoggerFactory.getLogger(RoutePlannerRealApiTest.class);

    @TempDir
    Path tempDir;

    @Test
    @Timeout(120)
    void hangzhouSixPoiRealMatrixAndFullChain() {
        // ---- 手工装配（真实高德 + 内存降级缓存，免 Redis 依赖） ----
        AppProperties props = new AppProperties();
        props.getAmap().setWebApiKey(System.getenv("AMAP_WEB_API_KEY"));
        TransportTool transportTool = new TransportTool(props);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new RuntimeException("no redis in test"));
        CommuteMatrixBuilder builder = new CommuteMatrixBuilder(transportTool,
                new TaskResultCache(redis));
        RoutePlannerServiceImpl planner = new RoutePlannerServiceImpl(builder,
                new TaskWorkspace(new CollabFileWriter(tempDir.toString()), new CollabFileReader()));

        List<PoiCandidate> pois = List.of(
                poi("西湖", 120.1449, 30.2436, 180),
                windowed("灵隐寺", 120.0962, 30.2435, 150, "07:00", "18:00"),
                windowed("雷峰塔", 120.1486, 30.2312, 120, "08:00", "17:30"),
                windowed("河坊街", 120.1700, 30.2400, 120, "09:00", "21:00"),
                poi("九溪烟树", 120.1100, 30.2000, 150),
                windowed("宋城", 120.0900, 30.1800, 150, "10:00", "21:00"));
        PoiCandidate hotel = new PoiCandidate("西湖文化酒店", 120.15, 30.25,
                null, null, null, null, "hotel-anchor");
        RouteConstraints c = RouteConstraints.ofDays(2).withHotel("西湖文化酒店", 120.15, 30.25);

        // ---- ① 真实矩阵：全部节点对高德可达（无 -1） ----
        List<PoiCandidate> nodes = new ArrayList<>(List.of(hotel));
        nodes.addAll(pois);
        MatrixBuildOutcome outcome = builder.build(nodes, "杭州", true);
        TimeMatrix matrix = outcome.matrix();
        log.info("[real-api] 矩阵构建: nodes={}, apiCalls={}, missingPairs={}",
                matrix.size(), outcome.apiCalls(), outcome.missingPairs().size());
        assertThat(outcome.missingPairs())
                .as("高德可达性：不应有查询失败的节点对")
                .isEmpty();
        for (int i = 0; i < matrix.size(); i++) {
            for (int j = 0; j < matrix.size(); j++) {
                if (i != j) {
                    assertThat(matrix.get(i, j))
                            .as("%s→%s 应可达", matrix.nodeNames().get(i), matrix.nodeNames().get(j))
                            .isGreaterThan(0);
                }
            }
        }
        assertThat(outcome.apiCalls())
                .as("7 节点共 21 对，每对至少一次查询")
                .isGreaterThanOrEqualTo(21);

        // ---- ② 全链 planAndWrite（矩阵缓存命中，零二次 API） ----
        RoutePlan plan = planner.planAndWrite("realapi", "conv-realapi-1", pois, c, "杭州");

        assertThat(plan.days()).hasSize(2);
        for (RoutePlan.RouteDay day : plan.days()) {
            assertThat(day.commuteMinutesTotal())
                    .as("day %d 通勤超上限", day.day())
                    .isLessThanOrEqualTo(c.maxDailyCommuteMin());
            List<RoutePlan.RoutePoi> ps = day.pois();
            for (int k = 0; k < ps.size(); k++) {
                RoutePlan.RoutePoi p = ps.get(k);
                assertThat(min(p.startTime()) < 13 * 60 && min(p.endTime()) > 12 * 60)
                        .as("day %d 第 %d 项占用午餐窗", day.day(), k)
                        .isFalse();
                int earliest = k == 0 ? 9 * 60 : min(ps.get(k - 1).endTime());
                assertThat(min(p.startTime()))
                        .as("day %d 第 %d 项时间链", day.day(), k)
                        .isGreaterThanOrEqualTo(earliest + p.commuteMinutesFromPrev()
                                + c.transferBufferMin());
            }
        }
        List<String> accounted = new ArrayList<>(plan.days().stream()
                .flatMap(d -> d.pois().stream()).map(RoutePlan.RoutePoi::name).toList());
        plan.solverMeta().unplaced().forEach(u -> accounted.add(u.name()));
        assertThat(accounted)
                .containsExactlyInAnyOrderElementsOf(
                        pois.stream().map(PoiCandidate::name).toList());
        assertThat(plan.solverMeta().algorithm()).isEqualTo("NN+2opt");
        assertThat(plan.solverMeta().matrixSource()).isEqualTo("amap-transit");

        // ---- ③ 人工可读检查点（真实数据路线方案打印到日志） ----
        log.info("[real-api] 路线方案（{} 天, unplaced={}, 耗时={}ms）:",
                plan.days().size(), plan.solverMeta().unplaced().size(),
                plan.solverMeta().durationMs());
        for (RoutePlan.RouteDay day : plan.days()) {
            StringBuilder line = new StringBuilder("  day " + day.day() + "（通勤 "
                    + day.commuteMinutesTotal() + "min）: ");
            for (RoutePlan.RoutePoi p : day.pois()) {
                line.append(p.startTime()).append('-').append(p.endTime())
                        .append(' ').append(p.name())
                        .append('（').append(p.transitFromPrev())
                        .append(p.commuteMinutesFromPrev()).append("min） → ");
            }
            log.info("{}", line.substring(0, line.length() - 3));
        }
    }

    private static PoiCandidate poi(String name, double lng, double lat, int durationMin) {
        return new PoiCandidate(name, lng, lat, null, null, durationMin, 4.5, "RAG");
    }

    private static PoiCandidate windowed(String name, double lng, double lat, int durationMin,
                                         String openStart, String openClose) {
        return new PoiCandidate(name, lng, lat, openStart, openClose, durationMin, 4.5, "RAG");
    }

    /** "HH:mm" → 当日分钟数 */
    private static int min(String hhmm) {
        String[] parts = hhmm.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }
}
