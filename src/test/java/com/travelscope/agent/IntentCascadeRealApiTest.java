package com.travelscope.agent;

import com.travelscope.config.AppProperties;
import com.travelscope.dto.IntentResult;
import com.travelscope.service.IntentCache;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 意图级联端到端测试（真实 qwen-turbo L2 + 真实 qwen-plus L3 + Fake 缓存）
 * <p>
 * 验证级联接线正确：L1 未命中的真实消息在 L2 被真实模型分类（验收标准 c 的完整链路），
 * 并观测 L2 真实延迟（目标 < 200ms 的观测项，网络抖动大不作硬断言）。
 * 2026-09-29 失忆修复 Fix 4：反问语境下裸地名走带上下文的真实 L2 判 PLANNING。
 * </p>
 */
@EnabledIfEnvironmentVariable(named = "API_KEY", matches = "sk-.+")
class IntentCascadeRealApiTest {

    /** Fake 缓存（复用单测语义；E2E 的 Redis 路径由 RedisIntentCacheTest 覆盖） */
    static class FakeCache implements IntentCache {
        final Map<String, String> l0 = new HashMap<>();
        final Map<String, String> l2 = new HashMap<>();

        @Override
        public String getRecentIntent(String userId, String sessionId) {
            return l0.get(userId + ":" + sessionId);
        }

        @Override
        public void saveRecentIntent(String userId, String sessionId, String intent, Duration ttl) {
            l0.put(userId + ":" + sessionId, intent);
        }

        @Override
        public String getCachedL2(String normalizedMessage) {
            return l2.get(normalizedMessage);
        }

        @Override
        public void saveCachedL2(String normalizedMessage, String intent, Duration ttl) {
            l2.put(normalizedMessage, intent);
        }
    }

    /** Fix 4 场景上下文：intake 反问「您想去哪里玩」后用户只答地名 */
    private static final String CLARIFY_CONTEXT = """
            会话摘要：用户想要一次旅行规划，需求收集进行中
            最近对话：
            用户：帮我规划一个行程
            助手：好的，请问您想去哪里玩？""";

    private static IntentCascadeRouter router;
    private static FakeCache cache;

    @BeforeAll
    static void setUp() {
        String apiKey = System.getenv("API_KEY");
        DashScopeChatModel turbo = DashScopeChatModel.builder()
                .apiKey(apiKey).modelName("qwen-turbo").stream(true).build();
        DashScopeChatModel plus = DashScopeChatModel.builder()
                .apiKey(apiKey).modelName("qwen-plus").stream(true).build();

        LightweightIntentClassifier l2 = new LightweightIntentClassifier(turbo);
        IntentClassifier l3 = new IntentClassifier(plus);

        AppProperties props = new AppProperties();
        cache = new FakeCache();
        router = new IntentCascadeRouter(props, cache, l2::classify, l3::classify);
    }

    @Test
    @DisplayName("E2E 验收标准 c：真实链路分类「帮我规划杭州三日游」→ PLANNING（L2 或 L3）")
    void testE2e_planningMessage_classifiedAsPlanning() {
        long start = System.nanoTime();
        IntentResult result = router.classify("帮我规划杭州三日游", null, "1", "conv-e2e");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("[E2E] 规划消息分类耗时: " + elapsedMs + "ms, intent="
                + (result != null ? result.intent : "null"));

        assertNotNull(result, "级联不应全 miss（L2/L3 至少一层成功）");
        assertEquals("PLANNING", result.intent,
                "「帮我规划杭州三日游」最终应判 PLANNING（验收标准 c）");

        // L2 结果写回了 L0/L2 缓存
        assertEquals("PLANNING", cache.getRecentIntent("1", "conv-e2e"));
    }

    @Test
    @DisplayName("E2E 验收标准 d：同会话追问「那改成四天呢」→ L0 沿用（真实缓存写回已发生）")
    void testE2e_followupHitsL0() {
        // 第一轮真实分类（写回 L0）
        IntentResult first = router.classify("我想去成都吃好吃的玩三天", null, "1", "conv-e2e-2");
        assertNotNull(first);
        IntentType firstType = first.toIntentType();
        assertNotNull(firstType);

        // 第二轮短追问 → L0 沿用第一轮意图（零模型调用，瞬时返回）
        long start = System.nanoTime();
        IntentResult followup = router.classify("那改成四天呢", null, "1", "conv-e2e-2");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("[E2E] 追问 L0 命中耗时: " + elapsedMs + "ms, intent=" + followup.intent);

        assertEquals(firstType, followup.toIntentType(), "L0 应沿用上一轮意图");
        assertTrue(elapsedMs < 100, "L0 命中应为内存级延迟（<100ms），实测 " + elapsedMs + "ms");
        assertTrue(followup.reason.contains("L0"), "应标注 L0 来源: " + followup.reason);
    }

    @Test
    @DisplayName("E2E 失忆修复 Fix 4：反问语境下裸地名「长春」→ PLANNING（带上下文走真实级联）")
    void testE2e_bareAnswerWithContext_planning() {
        long start = System.nanoTime();
        IntentResult result = router.classify("长春", CLARIFY_CONTEXT, "1", "conv-e2e-ctx");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("[E2E] 带上下文裸地名分类耗时: " + elapsedMs + "ms, intent="
                + (result != null ? result.intent : "null"));

        assertNotNull(result, "级联不应全 miss");
        assertEquals("PLANNING", result.intent,
                "反问语境下的「长春」应判 PLANNING（不再被判 CHAT 脱离规划流）");
        assertNull(cache.getCachedL2("长春"), "带上下文的判定不写全局文本缓存（防投毒）");
        assertEquals("PLANNING", cache.getRecentIntent("1", "conv-e2e-ctx"), "L0 最近意图正常写回");
    }

    @Test
    @DisplayName("E2E：L1 命中的消息不触发任何真实模型调用（验收标准 a）")
    void testE2e_l1Hit_noModelCall() {
        // 用 L2 真实分类器但统计调用：L1 命中时它不应被调用——
        // 这里通过缓存佐证：打招呼消息不产生 L2 文本缓存条目
        router.classify("你好", null, "1", "conv-e2e-3");
        assertNullCached("你好");
    }

    private void assertNullCached(String msg) {
        // L1 命中只写 L0 最近意图，不写 L2 文本缓存
        assertTrue(cache.getCachedL2(msg) == null, "L1 命中不应写 L2 文本缓存");
    }
}
