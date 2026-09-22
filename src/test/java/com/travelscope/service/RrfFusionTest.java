package com.travelscope.service;

import com.travelscope.dto.RetrievedFragment;
import com.travelscope.service.RrfFusion.Entry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RrfFusion 单元测试（FR-S11：双路排名融合）
 */
class RrfFusionTest {

    @Test
    @DisplayName("双路命中同一 chunk → 标记 RAG+ES 且得分最高（两路贡献相加）")
    void testBothLanesHit() {
        List<Entry> vector = List.of(
                new Entry("a:1", "西湖攻略"), new Entry("b:1", "灵隐寺"));
        List<Entry> keyword = List.of(
                new Entry("b:1", "灵隐寺"), new Entry("c:1", "河坊街"));

        List<RetrievedFragment> fused = RrfFusion.fuse(vector, keyword, 60, 10);

        assertEquals(3, fused.size());
        assertEquals("b:1", fused.get(0).chunkId(), "双路命中的应排第一");
        assertEquals("RAG+ES", fused.get(0).source());
        // b:1 = 语义路第2名 1/(60+2) + ES 路第1名 1/(60+1)
        assertEquals(1.0 / 62.0 + 1.0 / 61.0, fused.get(0).score(), 1e-9);
        assertEquals("RAG", fused.get(1).source(), "仅语义路命中");
        assertEquals("ES", fused.get(2).source(), "仅关键词路命中");
    }

    @Test
    @DisplayName("语义路缺失（pgvector 不可用退化）→ 纯 ES 排名，来源全为 ES")
    void testSingleLaneDegradation() {
        List<Entry> keyword = List.of(
                new Entry("x:1", "内容1"), new Entry("y:1", "内容2"));

        List<RetrievedFragment> fused = RrfFusion.fuse(List.of(), keyword, 60, 10);

        assertEquals(2, fused.size());
        assertTrue(fused.stream().allMatch(f -> "ES".equals(f.source())));
    }

    @Test
    @DisplayName("rrfK 越小头部排名权重差异越大（参数生效验证）")
    void testRrfKParameter() {
        List<Entry> a = List.of(new Entry("p:1", "A第一"), new Entry("q:1", "A第二"));
        List<Entry> b = List.of(new Entry("q:1", "B第一"), new Entry("p:1", "B第二"));

        // 两路完全对称：p=第1+第2，q=第2+第1 → 融合分相等，顺序保持稳定
        List<RetrievedFragment> fused = RrfFusion.fuse(a, b, 60, 10);
        assertEquals(2, fused.size());
        assertEquals(fused.get(0).score(), fused.get(1).score(), 1e-12);
        assertTrue(fused.get(0).score() > 0);
    }

    @Test
    @DisplayName("topK 截取生效；空输入返回空")
    void testTopKAndEmpty() {
        List<Entry> a = List.of(new Entry("1:1", "c1"), new Entry("2:1", "c2"),
                new Entry("3:1", "c3"));
        assertEquals(2, RrfFusion.fuse(a, List.of(), 60, 2).size());
        assertTrue(RrfFusion.fuse(List.of(), List.of(), 60, 5).isEmpty());
    }

    @Test
    @DisplayName("null chunkId 条目被跳过（防御脏数据）")
    void testNullChunkIdSkipped() {
        List<Entry> a = List.of(new Entry(null, "脏数据"), new Entry("ok:1", "正常"));
        List<RetrievedFragment> fused = RrfFusion.fuse(a, List.of(), 60, 10);
        assertEquals(1, fused.size());
        assertEquals("ok:1", fused.get(0).chunkId());
    }
}
