package com.travelscope.agent;

import com.travelscope.config.AppProperties;
import com.travelscope.dto.IntentResult;
import com.travelscope.service.IntentCache;
import com.travelscope.testutil.ModelGatewayTestSupport;
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
 * 意图级联端到端测试（真实主池 glm L2 + L3 @ 模型网关 + Fake 缓存，v5-F3 起与生产同款）
 * <p>
 * 验证级联接线正确：L1 未命中的真实消息在 L2 被真实模型分类（验收标准 c 的完整链路），
 * 并观测 L2 真实延迟（网络抖动大不作硬断言）。
 * 2026-09-29 失忆修复 Fix 4：反问语境下裸地名走带上下文的真实 L2 判 PLANNING。
 * 2026-10-22 v7 意图改造：收集中确认短回答（「这样可以吗」）/ 完成态新查询
 * （「那边天气如何」）两类场景的真模型验证（状态行上下文 + 新提示词）。
 * </p>
 */
@EnabledIfEnvironmentVariable(named = ModelGatewayTestSupport.API_KEY_ENV, matches = ".+")
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

    /** v7 意图改造场景一：需求收集中，用户确认式短回答应回流规划流 */
    private static final String COLLECTING_CONTEXT = """
            会话状态：需求收集中（已收集: 目的地=西安; 天数=4；待补: startDate；已反问 1/3 轮）
            最近对话：
            用户：帮我规划西安四日游
            助手：好的，请问打算哪天出发？""";

    /** v7 意图改造场景二：行程已生成（完成态），自身意图明确的新查询不应被带回 PLANNING */
    private static final String DRAFT_DONE_CONTEXT = """
            会话状态：行程已生成（用户可能就现有行程追问、确认或要求调整）
            最近对话：
            用户：帮我规划西安四日游
            助手：（已生成西安四日行程草案）""";

    private static IntentCascadeRouter router;
    private static FakeCache cache;

    @BeforeAll
    static void setUp() {
        // v5-F3 起生产 L2 走主池（glm 优先），E2E 与生产同款
        LightweightIntentClassifier l2 = new LightweightIntentClassifier(ModelGatewayTestSupport.mainModel());
        IntentClassifier l3 = new IntentClassifier(ModelGatewayTestSupport.mainModel());

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

    @Test
    @DisplayName("E2E v7 场景一：收集中「这样可以吗」→ PLANNING（真实 L2 带状态行上下文）")
    void testE2e_confirmationDuringCollecting_planning() {
        long start = System.nanoTime();
        IntentResult result = router.classify("这样可以吗", COLLECTING_CONTEXT, "1", "conv-e2e-v7a");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("[E2E] 收集中确认短回答分类耗时: " + elapsedMs + "ms, intent="
                + (result != null ? result.intent : "null")
                + ", reason=" + (result != null ? result.reason : "null"));

        assertNotNull(result, "级联不应全 miss");
        assertEquals("PLANNING", result.intent,
                "收集中确认式短回答应回流规划流（reason=" + result.reason + "）");
    }

    @Test
    @DisplayName("E2E v7 场景二：行程已生成「那边天气如何」→ TOOL_CALL（消息自身意图明确）")
    void testE2e_weatherAfterDraft_toolCall() {
        long start = System.nanoTime();
        IntentResult result = router.classify("那边天气如何", DRAFT_DONE_CONTEXT, "1", "conv-e2e-v7b");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("[E2E] 完成态新查询分类耗时: " + elapsedMs + "ms, intent="
                + (result != null ? result.intent : "null")
                + ", reason=" + (result != null ? result.reason : "null"));

        assertNotNull(result, "级联不应全 miss");
        assertEquals("TOOL_CALL", result.intent,
                "天气查询应按消息本身判定，不被完成态带偏（reason=" + result.reason + "）");
    }

    private void assertNullCached(String msg) {
        // L1 命中只写 L0 最近意图，不写 L2 文本缓存
        assertTrue(cache.getCachedL2(msg) == null, "L1 命中不应写 L2 文本缓存");
    }
}
