package com.travelscope.service.route;

import com.travelscope.agent.tools.TransportTool;
import com.travelscope.service.TaskResultCache;
import com.travelscope.service.route.dto.PoiCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S1.2 验证：CommuteMatrixBuilder。
 * mock TransportTool 返回固定 JSON；真实 TaskResultCache + 永远失败的
 * redisTemplate mock（走 memoryFallback 降级，缓存真实生效）。
 */
class CommuteMatrixBuilderTest {

    /** 西湖（A）→ 雷峰塔（B）≈1.4km（<2km 驾车档）；→ 灵隐寺（C）≈4.7/5.0km（≥2km 公交档） */
    private static final PoiCandidate WEST_LAKE =
            new PoiCandidate("西湖", 120.1449, 30.2436, null, null, 240, 4.8, "RAG");
    private static final PoiCandidate LEIFENG =
            new PoiCandidate("雷峰塔", 120.1486, 30.2312, "08:00", "17:30", 120, 4.6, "ES");
    private static final PoiCandidate LINGYIN =
            new PoiCandidate("灵隐寺", 120.0962, 30.2435, "07:00", "18:00", 150, 4.7, "RAG");

    private TransportTool transportTool;
    private CommuteMatrixBuilder builder;

    @BeforeEach
    void setUp() {
        transportTool = mock(TransportTool.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("no redis in test"));
        builder = new CommuteMatrixBuilder(transportTool, new TaskResultCache(redisTemplate));
    }

    private static String drivingJson(long seconds) {
        return "{\"duration\":\"" + seconds + "\",\"distance\":\"1500\"}";
    }

    private static String transitJson(long seconds) {
        return "{\"count\":1,\"routes\":[{\"duration\":\"" + seconds + "\",\"distance\":\"8000\"}]}";
    }

    private static String errorJson() {
        return "{\"error\":\"高德 API 请求失败，HTTP 500\"}";
    }

    @Test
    void threeNodeMatrixCorrect() {
        when(transportTool.getDrivingRoute("120.1449,30.2436", "120.1486,30.2312"))
                .thenReturn(drivingJson(420));
        when(transportTool.getTransitRoute("120.1449,30.2436", "120.0962,30.2435", "杭州"))
                .thenReturn(transitJson(900));
        when(transportTool.getTransitRoute("120.1486,30.2312", "120.0962,30.2435", "杭州"))
                .thenReturn(transitJson(1080));

        MatrixBuildOutcome outcome = builder.build(List.of(WEST_LAKE, LEIFENG, LINGYIN), "杭州", true);

        assertThat(outcome.cacheHit()).isFalse();
        assertThat(outcome.apiCalls()).isEqualTo(3);
        assertThat(outcome.missingPairs()).isEmpty();
        var m = outcome.matrix();
        assertThat(m.size()).isEqualTo(3);
        assertThat(m.nodeNames()).containsExactly("西湖", "雷峰塔", "灵隐寺");
        assertThat(m.get(0, 0)).isZero();
        assertThat(m.get(0, 1)).isEqualTo(420);
        assertThat(m.get(1, 0)).isEqualTo(420);
        assertThat(m.get(0, 2)).isEqualTo(900);
        assertThat(m.get(1, 2)).isEqualTo(1080);
        assertThat(m.get(2, 1)).isEqualTo(1080);
    }

    @Test
    void failureFallsBackToOtherModeThenMinusOne() {
        // A→B：驾车 error → 公交顶上（600）
        when(transportTool.getDrivingRoute("120.1449,30.2436", "120.1486,30.2312"))
                .thenReturn(errorJson());
        when(transportTool.getTransitRoute("120.1449,30.2436", "120.1486,30.2312", "杭州"))
                .thenReturn(transitJson(600));
        // A→C：公交正常
        when(transportTool.getTransitRoute("120.1449,30.2436", "120.0962,30.2435", "杭州"))
                .thenReturn(transitJson(900));
        // B→C：公交 error → 驾车也 error → -1
        when(transportTool.getTransitRoute("120.1486,30.2312", "120.0962,30.2435", "杭州"))
                .thenReturn(errorJson());
        when(transportTool.getDrivingRoute("120.1486,30.2312", "120.0962,30.2435"))
                .thenReturn(errorJson());

        MatrixBuildOutcome outcome = builder.build(List.of(WEST_LAKE, LEIFENG, LINGYIN), "杭州", true);

        assertThat(outcome.matrix().get(0, 1)).isEqualTo(600);
        assertThat(outcome.matrix().get(0, 2)).isEqualTo(900);
        assertThat(outcome.matrix().get(1, 2)).isEqualTo(-1);
        assertThat(outcome.matrix().get(2, 1)).isEqualTo(-1);
        assertThat(outcome.missingPairs()).containsExactly("雷峰塔→灵隐寺");
    }

    @Test
    void segmentModeSelectedByStraightDistance() {
        when(transportTool.getDrivingRoute("120.1449,30.2436", "120.1486,30.2312"))
                .thenReturn(drivingJson(420));
        when(transportTool.getTransitRoute("120.1449,30.2436", "120.0962,30.2435", "杭州"))
                .thenReturn(transitJson(900));
        when(transportTool.getTransitRoute("120.1486,30.2312", "120.0962,30.2435", "杭州"))
                .thenReturn(transitJson(1080));

        builder.build(List.of(WEST_LAKE, LEIFENG, LINGYIN), "杭州", true);

        // 近距对（西湖→雷峰塔 ≈1.4km）走驾车；两个远距对走公交
        verify(transportTool, times(1))
                .getDrivingRoute("120.1449,30.2436", "120.1486,30.2312");
        verify(transportTool, times(1))
                .getTransitRoute("120.1449,30.2436", "120.0962,30.2435", "杭州");
        verify(transportTool, times(1))
                .getTransitRoute("120.1486,30.2312", "120.0962,30.2435", "杭州");
        verify(transportTool, never())
                .getDrivingRoute("120.1449,30.2436", "120.0962,30.2435");
    }

    @Test
    void matrixCacheHitSkipsAllApiCalls() {
        when(transportTool.getDrivingRoute("120.1449,30.2436", "120.1486,30.2312"))
                .thenReturn(drivingJson(420));
        when(transportTool.getTransitRoute("120.1449,30.2436", "120.0962,30.2435", "杭州"))
                .thenReturn(transitJson(900));
        when(transportTool.getTransitRoute("120.1486,30.2312", "120.0962,30.2435", "杭州"))
                .thenReturn(transitJson(1080));

        MatrixBuildOutcome first = builder.build(List.of(WEST_LAKE, LEIFENG, LINGYIN), "杭州", true);
        MatrixBuildOutcome second = builder.build(List.of(WEST_LAKE, LEIFENG, LINGYIN), "杭州", true);

        assertThat(first.cacheHit()).isFalse();
        assertThat(second.cacheHit()).isTrue();
        assertThat(second.apiCalls()).isZero();
        assertThat(second.matrix()).isEqualTo(first.matrix());
        // API 调用总数未增长
        verify(transportTool, times(1))
                .getDrivingRoute("120.1449,30.2436", "120.1486,30.2312");
        verify(transportTool, times(1))
                .getTransitRoute("120.1449,30.2436", "120.0962,30.2435", "杭州");
    }

    @Test
    void geocodeBackfillsMissingCoordinatesOnce() {
        PoiCandidate jiuxi = new PoiCandidate("九溪烟树", null, null, null, null, 120, 4.5, "RAG");
        // 注意：坐标字符串经 Double 解析后尾随零丢失（120.1000→"120.1"），mock 须用解析后格式
        PoiCandidate yanggongdi = new PoiCandidate("杨公堤", 120.15, 30.25, null, null, 90, 4.4, "RAG");
        when(transportTool.geocode("九溪烟树"))
                .thenReturn("{\"location\":\"120.1,30.2\",\"formatted_address\":\"杭州市西湖区九溪\"}");
        // 九溪(120.1,30.2)→西湖 ≈6km（公交）；九溪→杨公堤 ≈7km（公交）；西湖→杨公堤 ≈0.8km（驾车）
        when(transportTool.getTransitRoute("120.1449,30.2436", "120.1,30.2", "杭州"))
                .thenReturn(transitJson(720));
        when(transportTool.getTransitRoute("120.1,30.2", "120.15,30.25", "杭州"))
                .thenReturn(transitJson(780));
        when(transportTool.getDrivingRoute("120.1449,30.2436", "120.15,30.25"))
                .thenReturn(drivingJson(300));

        MatrixBuildOutcome first = builder.build(List.of(WEST_LAKE, jiuxi), "杭州", true);
        assertThat(first.matrix().get(0, 1)).isEqualTo(720);
        verify(transportTool, times(1)).geocode("九溪烟树");

        // 加一个点使矩阵 hash 变化（矩阵级 miss，对级无缓存 → 全部 3 对重查），
        // 但 geocode 应走 GEO 缓存不再调
        MatrixBuildOutcome second = builder.build(List.of(WEST_LAKE, jiuxi, yanggongdi), "杭州", true);
        assertThat(second.cacheHit()).isFalse();
        assertThat(second.apiCalls()).isEqualTo(3);
        verify(transportTool, times(1)).geocode("九溪烟树");
        assertThat(second.matrix().get(1, 2)).isEqualTo(780);
        assertThat(second.matrix().get(0, 2)).isEqualTo(300);
    }
}
