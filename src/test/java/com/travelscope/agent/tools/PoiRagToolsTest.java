package com.travelscope.agent.tools;

import com.travelscope.config.AppProperties;
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
            super(new AppProperties(), null, null, "u", "p");
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public List<RetrievedFragment> dualRetrieveWithSource(String query, int topK) {
            return List.of(
                    new RetrievedFragment("西湖是杭州最著名景点…坐标120.15,30.24", "RAG+ES", 0.031, "seed-1:1"),
                    new RetrievedFragment("灵隐寺千年古刹…开放07:00-18:00", "RAG", 0.016, "seed-2:1"));
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
}
