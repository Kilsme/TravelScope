package com.travelscope.agent.tools;

import com.travelscope.config.AppProperties;
import com.travelscope.llm.ProviderProbeScheduler;
import com.travelscope.dto.RetrievedFragment;
import com.travelscope.service.EsBm25Client;
import com.travelscope.service.RagServiceImpl;
import com.travelscope.service.TaskResultCache;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PoiRagTools 测试（Fake RagServiceImpl + 内存降级 TaskResultCache）
 * <p>
 * RagServiceImpl 是具体类（构造需要 PG/ES），测试用子类覆写检索方法打桩。
 * </p>
 */
class PoiRagToolsTest {

    /** opsForValue 等全抛异常 → TaskResultCache 内存降级（同 service 包的测试风格） */
    static class ThrowingRedisTemplate extends StringRedisTemplate {
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

    /** 检索桩：固定返回带来源标记的片段（构造传 null 绕开 PG/ES 初始化——只在覆写方法中使用） */
    static class StubRagService extends RagServiceImpl {
        StubRagService() {
            super(new AppProperties(), null, null, "u", "p", new ProviderProbeScheduler());
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public RagServiceImpl.GatedResult dualRetrieveGated(String query, int topK) {
            return new RagServiceImpl.GatedResult(List.of(
                    new RetrievedFragment("西湖是杭州最著名景点…坐标120.15,30.24", "RAG+ES", 0.031, "seed-1:1"),
                    new RetrievedFragment("灵隐寺千年古刹…开放07:00-18:00", "RAG", 0.016, "seed-2:1")),
                    0, false);
        }
    }

    /** 可变检索桩（P1-F 门控用）：返回片段与全灭标记由用例设置 */
    static class MutableStubRagService extends RagServiceImpl {
        private List<RetrievedFragment> fragments = List.of();
        private boolean gateEmpty;

        MutableStubRagService() {
            super(new AppProperties(), null, null, "u", "p", new ProviderProbeScheduler());
        }

        void setFragments(List<RetrievedFragment> fragments) {
            this.fragments = fragments;
        }

        void setGateEmpty(boolean gateEmpty) {
            this.gateEmpty = gateEmpty;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public RagServiceImpl.GatedResult dualRetrieveGated(String query, int topK) {
            return new RagServiceImpl.GatedResult(fragments, 0, gateEmpty);
        }

        @Override
        public double evidenceGateMinScore() {
            return 0.5;
        }
    }

    private PoiRagTools tools;
    private TaskResultCache cache;

    @BeforeEach
    void setUp() {
        cache = new TaskResultCache(new ThrowingRedisTemplate());
        tools = new PoiRagTools(new StubRagService(), cache);
    }

    private static RuntimeContext ctx() {
        return RuntimeContext.builder().userId("u1").sessionId("conv-t").build();
    }

    @Test
    @DisplayName("search_pois_with_rag：返回片段带来源标记（RAG/ES/RAG+ES）与 chunk 标识")
    void testSearchWithRagFormat() {
        String result = tools.search_pois_with_rag("杭州 必去景点", 5, ctx());
        assertTrue(result.contains("来源=RAG+ES"), "应有来源标记: " + result);
        assertTrue(result.contains("chunk=seed-1:1"), "应有 chunk 标识");
        assertTrue(result.contains("西湖"), "应含片段内容");
        assertTrue(result.contains("融合分="));
    }

    @Test
    @DisplayName("register → get 回环：get 返回可复用内容且剥离元信息")
    void testRegisterAndGetRoundTrip() {
        String reg = tools.register_task_result("poi", "T3", "# 景点候选清单\n西湖", "conv-t", ctx());
        assertTrue(reg.contains("已登记 poi"));

        String got = tools.get_cached_task_result("poi", "conv-t", ctx());
        assertTrue(got.contains("缓存命中"), "应提示命中: " + got);
        assertTrue(got.contains("# 景点候选清单"));
        assertTrue(got.contains("西湖"));
    }

    @Test
    @DisplayName("未登记 → CACHE_MISS 提示需正常执行")
    void testCacheMiss() {
        String got = tools.get_cached_task_result("route", "conv-t", ctx());
        assertTrue(got.startsWith("CACHE_MISS"), "应提示 miss: " + got);
    }

    @Test
    @DisplayName("非法 taskType 与空 content 拒绝")
    void testInvalidInputs() {
        assertTrue(tools.get_cached_task_result("foo", "conv-t", ctx()).startsWith("ERROR"));
        assertTrue(tools.register_task_result("poi", "T3", "", "conv-t", ctx()).startsWith("ERROR"));
    }

    @Test
    @DisplayName("sessionId 解析：传入 sub-xxx 时从 ctx 协作目录键回退主会话")
    void testSessionIdResolution() {
        RuntimeContext ctx = RuntimeContext.builder()
                .userId("u1").sessionId("sub-xyz").build();
        ctx.put("travelscope.collab.dir", "tasks/conv-99");
        tools.register_task_result("poi", "T3", "内容", "sub-xyz", ctx);
        // 用正确的 conv-99 查（证明落键用了主会话而非 sub-xxx）
        String got = tools.get_cached_task_result("poi", "conv-99", ctx());
        assertTrue(got.contains("缓存命中"), "应解析到主会话 conv-99: " + got);
    }

    /** 组装带可变桩的 tools（P1-F 门控用例） */
    private PoiRagTools toolsWith(List<RetrievedFragment> fragments) {
        MutableStubRagService stub = new MutableStubRagService();
        stub.setFragments(fragments);
        return new PoiRagTools(stub, cache);
    }

    @Test
    @DisplayName("P1-F 门控触发：双路共识=0 且总条数<3 → 输出末尾追加低置信警告")
    void testLowConfidenceGateTriggered() {
        PoiRagTools t = toolsWith(List.of(
                new RetrievedFragment("某某攻略片段A", "RAG", 0.016, "d1:1"),
                new RetrievedFragment("某某攻略片段B", "RAG", 0.015, "d2:1")));

        String result = t.search_pois_with_rag("不存在的地方 景点", 5, ctx());

        assertTrue(result.contains("置信度低"), "共识 0 + 条数过少应警告: " + result);
        assertTrue(result.contains("请勿据此编造 POI 信息"), "应含知情降级指引");
    }

    @Test
    @DisplayName("P1-F 门控不触发：含双路共识命中（RAG+ES）→ 无警告")
    void testGateSilentWhenConsensusExists() {
        PoiRagTools t = toolsWith(List.of(
                new RetrievedFragment("西湖攻略", "RAG+ES", 0.031, "seed-1:1"),
                new RetrievedFragment("灵隐寺攻略", "RAG", 0.016, "seed-2:1")));

        String result = t.search_pois_with_rag("杭州 景点", 5, ctx());

        assertTrue(!result.contains("置信度低"), "有共识命中不应警告: " + result);
    }

    @Test
    @DisplayName("P1-F 门控不触发：共识=0 但条数充足（≥3）→ 无警告（AND 语义防误报）")
    void testGateSilentWhenFragmentsEnough() {
        PoiRagTools t = toolsWith(List.of(
                new RetrievedFragment("片段A", "RAG", 0.016, "d1:1"),
                new RetrievedFragment("片段B", "ES", 0.015, "d2:1"),
                new RetrievedFragment("片段C", "RAG", 0.014, "d3:1")));

        String result = t.search_pois_with_rag("杭州 景点", 5, ctx());

        assertTrue(!result.contains("置信度低"), "条数充足时仅共识 0 不应警告: " + result);
    }

    @Test
    @DisplayName("证据门控全灭：召到了但全被门掉 → 「门控后无可用证据」文案（区别于检索无结果）")
    void testGateEmptyMessage() {
        MutableStubRagService stub = new MutableStubRagService();
        stub.setFragments(List.of());       // 门控后为空
        stub.setGateEmpty(true);            // 但门控前非空（召到了）
        PoiRagTools t = new PoiRagTools(stub, cache);

        String result = t.search_pois_with_rag("哈尔滨 冰雪大世界", 5, ctx());

        assertTrue(result.contains("相关性门控后无可用证据"), "全灭应有门控文案: " + result);
        assertTrue(result.contains("请勿编造 POI 信息"), "应含知情降级指引");
        assertTrue(result.contains("0.5"), "应带门控阈值");
        assertTrue(!result.contains("检索无结果"), "不应混用无结果文案");
    }

    @Test
    @DisplayName("真·检索无结果（gateEmpty=false）→ 原无结果文案不动")
    void testPlainEmptyMessage() {
        MutableStubRagService stub = new MutableStubRagService();
        stub.setFragments(List.of());
        stub.setGateEmpty(false);
        PoiRagTools t = new PoiRagTools(stub, cache);

        String result = t.search_pois_with_rag("不存在的 xxx", 5, ctx());

        assertTrue(result.contains("知识库检索无结果"), "没召到应走原文案: " + result);
        assertTrue(!result.contains("相关性门控"), "不应混用门控文案");
    }
}
