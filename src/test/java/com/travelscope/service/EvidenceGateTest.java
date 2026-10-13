package com.travelscope.service;

import com.travelscope.config.AppProperties;
import com.travelscope.dto.RetrievedFragment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 证据门控（绝对分数门控，清单 4.4 完整形态）单测——直测 RagServiceImpl.applyEvidenceGate
 * （不触达检索路，无环境门控）
 */
class EvidenceGateTest {

    private static RagServiceImpl newService(boolean gateEnabled, double minScore) {
        AppProperties props = new AppProperties();
        props.getRag().setEvidenceGateEnabled(gateEnabled);
        props.getRag().setEvidenceGateMinScore(minScore);
        return new RagServiceImpl(props, Mockito.mock(EsBm25Client.class), null, "u", "p");
    }

    private static RetrievedFragment frag(String chunkId, Double vectorScore) {
        return new RetrievedFragment("片段" + chunkId, "RAG", 0.01, chunkId, vectorScore);
    }

    @Test
    @DisplayName("高于阈值保留 / 低于阈值丢弃")
    void testAboveBelowThreshold() {
        RagServiceImpl service = newService(true, 0.5);
        List<RetrievedFragment> fused = List.of(frag("a:1", 0.8), frag("b:1", 0.6), frag("c:1", 0.3));

        RagServiceImpl.GatedResult result = service.applyEvidenceGate("q", fused);
        service.shutdownRetrievalExecutor();

        assertEquals(2, result.fragments().size(), "0.8/0.6 保留，0.3 丢弃");
        assertEquals(1, result.dropped());
        assertFalse(result.gateEmpty());
        assertEquals("a:1", result.fragments().get(0).chunkId(), "顺序保持（融合排序不动）");
    }

    @Test
    @DisplayName("ES-only 片段（无向量分）放行但参与全灭判定")
    void testEsOnlyFragmentsPass() {
        RagServiceImpl service = newService(true, 0.5);
        List<RetrievedFragment> fused = List.of(frag("es:1", null), frag("rag:1", 0.2));

        RagServiceImpl.GatedResult result = service.applyEvidenceGate("q", fused);
        service.shutdownRetrievalExecutor();

        assertEquals(1, result.fragments().size(), "无分 ES 片段应放行（BM25 无量纲可比分）");
        assertEquals("es:1", result.fragments().get(0).chunkId());
        assertEquals(1, result.dropped());
        assertFalse(result.gateEmpty(), "有放行片段不算全灭");
    }

    @Test
    @DisplayName("全灭：召到了但全部低于阈值 → gateEmpty=true（工具层区分「检索无结果」）")
    void testGateEmpty() {
        RagServiceImpl service = newService(true, 0.5);
        List<RetrievedFragment> fused = List.of(frag("a:1", 0.3), frag("b:1", 0.1));

        RagServiceImpl.GatedResult result = service.applyEvidenceGate("q", fused);
        service.shutdownRetrievalExecutor();

        assertTrue(result.fragments().isEmpty());
        assertTrue(result.gateEmpty(), "门控前非空、门控后为空 = 全灭");
        assertEquals(2, result.dropped());
    }

    @Test
    @DisplayName("开关关闭 → 直通不门控")
    void testDisabledPassthrough() {
        RagServiceImpl service = newService(false, 0.99);
        List<RetrievedFragment> fused = List.of(frag("a:1", 0.1));

        RagServiceImpl.GatedResult result = service.applyEvidenceGate("q", fused);
        service.shutdownRetrievalExecutor();

        assertEquals(1, result.fragments().size());
        assertEquals(0, result.dropped());
        assertFalse(result.gateEmpty());
    }

    @Test
    @DisplayName("空输入直通（不触发门控语义）")
    void testEmptyInputPassthrough() {
        RagServiceImpl service = newService(true, 0.5);

        RagServiceImpl.GatedResult result = service.applyEvidenceGate("q", List.of());
        service.shutdownRetrievalExecutor();

        assertTrue(result.fragments().isEmpty());
        assertFalse(result.gateEmpty(), "没召到 ≠ 全灭（gateEmpty 语义区分）");
        assertEquals(0, result.dropped());
    }
}
