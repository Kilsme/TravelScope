package com.travelscope.service.retrieval;

import com.travelscope.agent.tools.AttractionTool;
import com.travelscope.config.AppProperties;
import com.travelscope.llm.ProviderProbeScheduler;
import com.travelscope.dto.RetrievedFragment;
import com.travelscope.service.RagServiceImpl;
import com.travelscope.service.retrieval.dto.PoiCandidatePool;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S1.8 验证：PoiRetrievalService 批量检索编排。
 * 覆盖：chunkId 去重合并（融合分取最大、hitVariants 保序累计、source/vectorScore 取高分实例）/
 * 候选不足触发硬补全（按变体顺序逐个 searchPois，够数即停）/ 单变体异常降级 /
 * 全空池纯补全兜底；stats 四字段精确断言。
 * 打桩模式同 PoiRagToolsTest：StubRagService extends RagServiceImpl 覆写 dualRetrieveGated
 * （构造传 null 绕开 PG/ES 初始化，只在覆写方法中使用）。
 */
class PoiRetrievalServiceTest {

    /** 按 query 映射返回脚本结果的检索桩；throwOnQuery 匹配的 query 直接抛异常 */
    static class ScriptRagService extends RagServiceImpl {
        private final Map<String, GatedResult> script;
        private final String throwOnQuery;

        ScriptRagService(Map<String, GatedResult> script, String throwOnQuery) {
            super(new AppProperties(), null, null, "u", "p", new ProviderProbeScheduler());
            this.script = script;
            this.throwOnQuery = throwOnQuery;
        }

        @Override
        public GatedResult dualRetrieveGated(String query, int topK) {
            if (query.equals(throwOnQuery)) {
                throw new RuntimeException("stub boom");
            }
            GatedResult result = script.get(query);
            return result == null ? new GatedResult(List.of(), 0, false) : result;
        }
    }

    /** 变体确定性：[杭州, 西湖, 杭城, 自然风光, 山水] */
    private static PoiRetrievalService service(RagServiceImpl rag, AttractionTool attraction) {
        return new PoiRetrievalService(rag, attraction, new QueryExpander(
                Map.of("杭州", List.of("西湖", "杭城")),
                Map.of("自然风景", List.of("自然风光", "山水", "公园"))));
    }

    private static RetrievedFragment frag(String chunkId, String content, String source,
                                          double score, Double vectorScore) {
        return new RetrievedFragment(content, source, score, chunkId, vectorScore);
    }

    private static RagServiceImpl.GatedResult gated(List<RetrievedFragment> fragments) {
        return new RagServiceImpl.GatedResult(fragments, 0, false);
    }

    private static String attractionJson(String name, String source) {
        return "{\"name\":\"" + name + "\",\"address\":\"某某路1号\",\"location\":\"120.15,30.24\","
                + "\"rating\":\"4.6\",\"source\":\"" + source + "\"}";
    }

    private static String poisResponse(String... attractions) {
        return "{\"count\":" + attractions.length + ",\"attractions\":["
                + String.join(",", attractions) + "],\"sources\":{\"飞猪\":2,\"高德\":2}}";
    }

    // ---------- 用例 1：chunkId 去重合并 + 证据标注保留 ----------
    @Test
    void dedupMergesVariantsKeepingBestScoreAndEvidence() {
        Map<String, RagServiceImpl.GatedResult> script = new LinkedHashMap<>();
        script.put("杭州", gated(List.of(
                frag("seed-1:1", "西湖是杭州最著名景点…", "RAG", 0.05, 0.9),
                frag("seed-2:1", "灵隐寺千年古刹…", "RAG", 0.04, 0.8))));
        script.put("西湖", gated(List.of(
                frag("seed-1:1", "西湖（ES 词条版）…", "ES", 0.03, null),
                frag("seed-3:1", "雷峰塔夕照…", "RAG+ES", 0.02, 0.7))));
        AttractionTool attraction = mock(AttractionTool.class);

        PoiCandidatePool pool = service(new ScriptRagService(script, null), attraction)
                .retrieve("杭州", List.of("自然风景"), 1);

        // 去重后 3 候选，按融合分降序
        assertThat(pool.candidates()).hasSize(3);
        PoiCandidatePool.Candidate first = pool.candidates().get(0);
        assertThat(first.chunkId()).isEqualTo("seed-1:1");
        assertThat(first.score()).as("融合分取最大").isEqualTo(0.05);
        assertThat(first.source()).as("source 取高分实例").isEqualTo("RAG");
        assertThat(first.vectorScore()).as("vectorScore 取高分实例").isEqualTo(0.9);
        assertThat(first.hitVariants()).as("命中变体保序累计").containsExactly("杭州", "西湖");
        assertThat(pool.candidates().get(1).chunkId()).isEqualTo("seed-2:1");
        assertThat(pool.candidates().get(2).chunkId()).isEqualTo("seed-3:1");

        // stats 四字段精确断言
        assertThat(pool.stats().variantCount()).isEqualTo(5);
        assertThat(pool.stats().hitsByVariant()).containsExactly(
                Map.entry("杭州", 2), Map.entry("西湖", 2), Map.entry("杭城", 0),
                Map.entry("自然风光", 0), Map.entry("山水", 0));
        assertThat(pool.stats().totalAfterDedup()).isEqualTo(3);
        assertThat(pool.stats().completedCount()).isEqualTo(0);

        // 候选充足（3 ≥ 2×1）→ 不触发补全
        verify(attraction, never()).searchPois(any(), any(), anyInt());
    }

    // ---------- 用例 2：候选不足 → 按变体顺序硬补全，够数即停 ----------
    @Test
    void shortPoolTriggersHardCompletion() {
        Map<String, RagServiceImpl.GatedResult> script = new LinkedHashMap<>();
        script.put("杭州", gated(List.of(
                frag("seed-1:1", "西湖是杭州最著名景点…", "RAG", 0.05, 0.9))));
        AttractionTool attraction = mock(AttractionTool.class);
        when(attraction.searchPois("杭州", "杭州", 6)).thenReturn(poisResponse(
                attractionJson("灵隐寺", "飞猪"), attractionJson("河坊街", "高德")));
        when(attraction.searchPois("杭州", "西湖", 6)).thenReturn(poisResponse(
                attractionJson("苏堤", "飞猪"), attractionJson("雷峰塔", "高德"),
                attractionJson("花港观鱼", "飞猪")));

        // days=3 → 目标 6；检索仅 1 条 → 触发补全（两变体凑满即停）
        PoiCandidatePool pool = service(new ScriptRagService(script, null), attraction)
                .retrieve("杭州", List.of("自然风景"), 3);

        assertThat(pool.stats().totalAfterDedup()).isEqualTo(1);
        assertThat(pool.stats().completedCount()).isEqualTo(5);
        assertThat(pool.candidates()).hasSize(6);
        // 检索条目按分降序在前，补全条目按变体顺序追加在后
        assertThat(pool.candidates().get(0).chunkId()).isEqualTo("seed-1:1");
        assertThat(pool.candidates().get(1).chunkId()).isEqualTo("hard:飞猪:灵隐寺");
        assertThat(pool.candidates().get(2).chunkId()).isEqualTo("hard:高德:河坊街");
        assertThat(pool.candidates().get(3).chunkId()).isEqualTo("hard:飞猪:苏堤");
        assertThat(pool.candidates().get(5).chunkId()).isEqualTo("hard:飞猪:花港观鱼");
        PoiCandidatePool.Candidate completed = pool.candidates().get(1);
        assertThat(completed.source()).isEqualTo("HARD_COMPLETE");
        assertThat(completed.vectorScore()).isNull();
        assertThat(completed.hitVariants()).containsExactly("杭州");
        assertThat(completed.content()).contains("灵隐寺", "地址=", "坐标=", "来源=飞猪");

        // 够数即停：第三变体不再补全
        verify(attraction).searchPois("杭州", "杭州", 6);
        verify(attraction).searchPois("杭州", "西湖", 6);
        verify(attraction, never()).searchPois(any(), org.mockito.ArgumentMatchers.eq("杭城"), anyInt());
    }

    // ---------- 用例 3：单变体异常 → 独立降级，其余正常成池 ----------
    @Test
    void variantFailureDegradesGracefully() {
        Map<String, RagServiceImpl.GatedResult> script = new LinkedHashMap<>();
        script.put("杭州", gated(List.of(
                frag("seed-1:1", "西湖…", "RAG", 0.05, 0.9),
                frag("seed-2:1", "灵隐寺…", "RAG", 0.04, 0.8))));
        script.put("西湖", gated(List.of(
                frag("seed-3:1", "雷峰塔…", "RAG+ES", 0.02, 0.7))));
        AttractionTool attraction = mock(AttractionTool.class);

        // 变体「自然风光」检索直接抛异常
        PoiCandidatePool pool = service(new ScriptRagService(script, "自然风光"), attraction)
                .retrieve("杭州", List.of("自然风景"), 1);

        assertThat(pool.candidates()).hasSize(3);
        assertThat(pool.stats().hitsByVariant())
                .as("失败变体按 0 命中计入")
                .containsEntry("自然风光", 0);
        assertThat(pool.stats().totalAfterDedup()).isEqualTo(3);
        assertThat(pool.stats().completedCount()).isEqualTo(0);
    }

    // ---------- 用例 4：全空检索池 → 纯补全兜底 ----------
    @Test
    void emptyPoolFallsBackEntirelyToCompletion() {
        AttractionTool attraction = mock(AttractionTool.class);
        when(attraction.searchPois("杭州", "杭州", 4)).thenReturn(poisResponse(
                attractionJson("灵隐寺", "飞猪"), attractionJson("河坊街", "高德"),
                attractionJson("雷峰塔", "飞猪"), attractionJson("苏堤", "高德")));

        PoiCandidatePool pool = service(new ScriptRagService(Map.of(), null), attraction)
                .retrieve("杭州", List.of("自然风景"), 2);

        assertThat(pool.stats().totalAfterDedup()).isEqualTo(0);
        assertThat(pool.stats().completedCount()).isEqualTo(4);
        assertThat(pool.candidates()).hasSize(4)
                .allSatisfy(c -> assertThat(c.source()).isEqualTo("HARD_COMPLETE"));
        // 首个变体补满即停
        verify(attraction).searchPois("杭州", "杭州", 4);
        verify(attraction, never()).searchPois(any(), org.mockito.ArgumentMatchers.eq("西湖"), anyInt());
    }

    // ---------- 用例 5：L3 变体缓存 → 同需求第二轮零远程调用 ----------

    /** 计数版检索桩：dualRetrieveGated 调用计数 */
    static class CountingRagService extends RagServiceImpl {
        private final Map<String, GatedResult> script;
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        CountingRagService(Map<String, GatedResult> script) {
            super(new AppProperties(), null, null, "u", "p", new ProviderProbeScheduler());
            this.script = script;
        }

        @Override
        public GatedResult dualRetrieveGated(String query, int topK) {
            calls.incrementAndGet();
            GatedResult result = script.get(query);
            return result == null ? new GatedResult(List.of(), 0, false) : result;
        }
    }

    /** Redis 全灭 → TaskResultCache 走内存降级（TaskResultCacheTest 同款打桩） */
    static class ThrowingRedisTemplate
            extends org.springframework.data.redis.core.StringRedisTemplate {
        @Override
        public org.springframework.data.redis.core.ValueOperations<String, String> opsForValue() {
            throw new IllegalStateException("redis down");
        }

        @Override
        public Boolean hasKey(String key) {
            throw new IllegalStateException("redis down");
        }

        @Override
        public Boolean delete(String key) {
            throw new IllegalStateException("redis down");
        }
    }

    @Test
    void variantCacheSkipsRemoteCallsOnSecondRetrieve() {
        Map<String, RagServiceImpl.GatedResult> script = new LinkedHashMap<>();
        script.put("杭州", gated(List.of(
                frag("seed-1:1", "西湖是杭州最著名景点…", "RAG", 0.05, 0.9))));
        CountingRagService rag = new CountingRagService(script);
        com.travelscope.service.TaskResultCache cache =
                new com.travelscope.service.TaskResultCache(new ThrowingRedisTemplate());
        PoiRetrievalService svc = new PoiRetrievalService(rag, mock(AttractionTool.class),
                new QueryExpander(Map.of("杭州", List.of("西湖", "杭城")), Map.of()), cache);

        PoiCandidatePool first = svc.retrieve("杭州", List.of(), 1);
        int firstCalls = rag.calls.get();
        assertThat(firstCalls).as("首轮每变体一次远程检索")
                .isEqualTo(first.stats().variantCount()).isPositive();
        assertThat(first.candidates()).isNotEmpty();

        PoiCandidatePool second = svc.retrieve("杭州", List.of(), 1);
        assertThat(rag.calls.get()).as("第二轮全部变体缓存命中，零远程调用").isEqualTo(firstCalls);
        assertThat(second.candidates()).as("缓存命中的候选内容一致")
                .extracting(PoiCandidatePool.Candidate::content)
                .isEqualTo(first.candidates().stream().map(PoiCandidatePool.Candidate::content).toList());
    }
}
