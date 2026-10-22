package com.travelscope.service;

import com.travelscope.config.AppProperties;
import com.travelscope.dto.RetrievedFragment;
import com.travelscope.llm.ProviderProbeScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RagServiceImpl 双路并行 + 通道级超时单测（P1-C，无环境门控）
 * <p>
 * Mockito 打桩 EsBm25Client；DataSource 传 null——语义路构造失败自动降级
 * （与 RagServiceImplTest 同款手法），聚焦 ES 路的并行/超时时序。
 * laneTimeoutMs 调小（200ms）加速验证。
 * </p>
 */
class RagServiceImplParallelTest {

    private static RagServiceImpl newService(EsBm25Client esClient, int laneTimeoutMs) {
        AppProperties props = new AppProperties();
        props.getRag().setLaneTimeoutMs(laneTimeoutMs);
        return new RagServiceImpl(props, esClient, null, "root", "root", new ProviderProbeScheduler());
    }

    @Test
    @DisplayName("通道级超时：慢 ES 路（2s）超过 laneTimeoutMs(200ms) → 空结果降级且不被拖住")
    void testSlowLaneDegradesWithinLaneTimeout() {
        EsBm25Client es = Mockito.mock(EsBm25Client.class);
        Mockito.when(es.isAvailable()).thenReturn(true);
        Mockito.when(es.search(Mockito.anyString(), Mockito.anyInt())).thenAnswer(inv -> {
            Thread.sleep(2000);   // 模拟慢 ES 路
            return List.of(new RrfFusion.Entry("d1:1", "杭州西湖"));
        });
        RagServiceImpl service = newService(es, 200);

        long start = System.nanoTime();
        List<RetrievedFragment> result = service.dualRetrieveWithSource("杭州 景点", 5);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        service.shutdownRetrievalExecutor();

        assertTrue(result.isEmpty(), "超时路应按空结果降级（两路均空 → 融合为空）");
        assertTrue(elapsedMs < 1500,
                "通道级超时只放弃等待不拖住调用方（实际 " + elapsedMs + "ms，应 ≪ 慢路 2000ms）");
    }

    @Test
    @DisplayName("行为兼容：正常 ES 路 → 融合结果非空、来源标记合法")
    void testNormalLaneStillFused() {
        EsBm25Client es = Mockito.mock(EsBm25Client.class);
        Mockito.when(es.isAvailable()).thenReturn(true);
        Mockito.when(es.search(Mockito.anyString(), Mockito.anyInt())).thenReturn(List.of(
                new RrfFusion.Entry("d1:1", "杭州西湖游玩攻略"),
                new RrfFusion.Entry("d2:1", "灵隐寺游览指南")));
        RagServiceImpl service = newService(es, 200);

        List<RetrievedFragment> result = service.dualRetrieveWithSource("杭州 景点", 5);
        service.shutdownRetrievalExecutor();

        assertEquals(2, result.size(), "ES 路 2 条应融合返回（语义路降级为空）");
        assertTrue(result.stream().allMatch(f ->
                        "ES".equals(f.source()) || "RAG".equals(f.source()) || "RAG+ES".equals(f.source())),
                "来源标记应合法");
    }

    @Test
    @DisplayName("P1-E 扇出/最终条数拆分：召回走 recallFanout(15)，融合截取=contextTopK")
    void testFanoutAndContextTopKSplit() {
        EsBm25Client es = Mockito.mock(EsBm25Client.class);
        Mockito.when(es.isAvailable()).thenReturn(true);
        // 召回侧返回 20 条（≥ 默认扇出 15 与最终条数 5，足以区分两个旋钮）
        List<RrfFusion.Entry> recalled = new java.util.ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            recalled.add(new RrfFusion.Entry("d" + i + ":1", "片段" + i));
        }
        Mockito.when(es.search(Mockito.anyString(), Mockito.anyInt())).thenReturn(recalled);
        RagServiceImpl service = newService(es, 200);

        List<RetrievedFragment> result = service.dualRetrieveWithSource("杭州 景点", 5);
        service.shutdownRetrievalExecutor();

        Mockito.verify(es).search(Mockito.anyString(), Mockito.eq(15));
        // 召回侧应使用 recallFanout 默认值 15（而非入参 5）——「一个 int 三义」拆分
        assertEquals(5, result.size(), "融合后截取应等于 contextTopK 入参（不再等于召回数）");
    }

    @Test
    @DisplayName("P1-E 启动校验：recallFanout < contextTopK 违反漏斗单调收窄 → 构造报错")
    void testFanoutSmallerThanContextTopKFailsFast() {
        AppProperties props = new AppProperties();
        props.getRag().setRecallFanout(3);
        props.getRag().setContextTopK(5);
        EsBm25Client es = Mockito.mock(EsBm25Client.class);

        IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> new RagServiceImpl(props, es, null, "root", "root", new ProviderProbeScheduler()));
        assertTrue(ex.getMessage().contains("漏斗单调收窄"), "报错应点明不变式: " + ex.getMessage());
    }
}
