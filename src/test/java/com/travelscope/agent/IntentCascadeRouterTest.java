package com.travelscope.agent;

import com.travelscope.config.AppProperties;
import com.travelscope.config.AppProperties.L1Rule;
import com.travelscope.dto.IntentResult;
import com.travelscope.service.IntentCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IntentCascadeRouter 纯单元测试（无 Spring、无 Redis、无模型，直接跑）
 * <p>
 * 用例逐条映射 FR-S01 验收标准 a~e：
 * a. "你好" → cascade_hit=L1，L2/L3 桩零调用（等价 DashScope 调用数不变）
 * b. "/天气 北京" → L1 命中 TOOL_CALL
 * c. "帮我规划杭州三日游" → L1 不命中（负向回归）→ L2 判 PLANNING
 * d. 同会话先发 c 再追问"那改成四天呢" → cascade_hit=L0
 * e. L1 命中平均延迟 < 10ms（纳秒计时，循环 100 次）
 * </p>
 * <p>
 * 2026-09-29 失忆修复 Fix 4：上下文注入（透传 L2/L3、带上下文旁路 L2 文本缓存、
 * 不影响 L0/L1）。
 * </p>
 */
class IntentCascadeRouterTest {

    /** 手写 Fake 缓存：HashMap 实现 L0/L2 语义 */
    static class FakeIntentCache implements IntentCache {
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

    /** 全抛异常的缓存：模拟 Redis 彻底不可用 */
    static class ThrowingIntentCache implements IntentCache {
        @Override
        public String getRecentIntent(String userId, String sessionId) {
            throw new IllegalStateException("redis down");
        }

        @Override
        public void saveRecentIntent(String userId, String sessionId, String intent, Duration ttl) {
            throw new IllegalStateException("redis down");
        }

        @Override
        public String getCachedL2(String normalizedMessage) {
            throw new IllegalStateException("redis down");
        }

        @Override
        public void saveCachedL2(String normalizedMessage, String intent, Duration ttl) {
            throw new IllegalStateException("redis down");
        }
    }

    /** 计数桩：记录调用次数与最近一次入参，返回预设结果 */
    static class CountingStub implements IntentCascadeRouter.LlmClassifier {
        final AtomicInteger calls = new AtomicInteger();
        volatile String lastMessage;
        volatile String lastContext;
        IntentResult next;

        CountingStub(IntentResult next) {
            this.next = next;
        }

        @Override
        public IntentResult classify(String userMessage, String contextBlock, String userId, String sessionId) {
            calls.incrementAndGet();
            this.lastMessage = userMessage;
            this.lastContext = contextBlock;
            return next;
        }
    }

    private static final String USER = "1";
    private static final String SESSION = "conv-99";

    private FakeIntentCache cache;
    private CountingStub l2Stub;
    private CountingStub l3Stub;
    private IntentCascadeRouter router;

    @BeforeEach
    void setUp() {
        cache = new FakeIntentCache();
        // 默认：L2 返回 PLANNING，L3 返回 CHAT（可被各用例覆盖）
        l2Stub = new CountingStub(new IntentResult("PLANNING", "l2 stub"));
        l3Stub = new CountingStub(new IntentResult("CHAT", "l3 stub"));
        router = newRouter(new AppProperties(), cache, l2Stub, l3Stub);
    }

    private IntentCascadeRouter newRouter(AppProperties props, IntentCache cache,
                                          IntentCascadeRouter.LlmClassifier l2,
                                          IntentCascadeRouter.LlmClassifier l3) {
        return new IntentCascadeRouter(props, cache, l2, l3);
    }

    // ==================== 验收标准 a：打招呼 → L1，零模型调用 ====================

    @Test
    @DisplayName("标准a：发「你好」→ L1 命中 CHAT，L2/L3 零调用")
    void testCriterionA_greetingHitsL1ZeroModelCalls() {
        IntentResult result = router.classify("你好", null, USER, SESSION);

        assertNotNull(result);
        assertEquals(IntentType.CHAT, result.toIntentType());
        assertEquals(0, l2Stub.calls.get(), "L2 不应被调用（DashScope 调用数不变）");
        assertEquals(0, l3Stub.calls.get(), "L3 不应被调用（DashScope 调用数不变）");
        // 命中后写回 L0 最近意图
        assertEquals("CHAT", cache.getRecentIntent(USER, SESSION));
    }

    // ==================== 验收标准 b：命令前缀 → L1 TOOL_CALL ====================

    @Test
    @DisplayName("标准b：发「/天气 北京」→ L1 命中 TOOL_CALL，零模型调用")
    void testCriterionB_weatherCommandHitsL1() {
        IntentResult result = router.classify("/天气 北京", null, USER, SESSION);

        assertNotNull(result);
        assertEquals(IntentType.TOOL_CALL, result.toIntentType());
        assertEquals(0, l2Stub.calls.get());
        assertEquals(0, l3Stub.calls.get());
    }

    // ==================== 验收标准 c：规划自然语言 → 不命中 L1，落 L2 ====================

    @Test
    @DisplayName("标准c：发「帮我规划杭州三日游」→ L1 不命中（负向回归），L2 判 PLANNING")
    void testCriterionC_planningFallsThroughToL2() {
        IntentResult result = router.classify("帮我规划杭州三日游", null, USER, SESSION);

        assertNotNull(result);
        assertEquals(IntentType.PLANNING, result.toIntentType());
        assertEquals(1, l2Stub.calls.get(), "L1 未命中应落到 L2");
        assertEquals(0, l3Stub.calls.get(), "L2 命中后不应到 L3");
        // L2 结果写回 L0 与文本缓存
        assertEquals("PLANNING", cache.getRecentIntent(USER, SESSION));
        assertEquals("PLANNING", cache.getCachedL2("帮我规划杭州三日游"));
    }

    @Test
    @DisplayName("标准c'：L2 返回 null → 落到 L3 兜底")
    void testCriterionC_l2NullFallsToL3() {
        l2Stub.next = null;
        IntentResult result = router.classify("帮我规划杭州三日游", null, USER, SESSION);

        assertNotNull(result);
        assertEquals("CHAT", result.intent, "应返回 L3 结果");
        assertEquals(1, l2Stub.calls.get());
        assertEquals(1, l3Stub.calls.get(), "L2 miss 后应调用 L3");
    }

    @Test
    @DisplayName("标准c''：L2 返回非法标签 → 视为 miss 落 L3")
    void testCriterionC_l2InvalidLabelFallsToL3() {
        l2Stub.next = new IntentResult("NOT_A_LABEL", "bad");
        IntentResult result = router.classify("帮我规划杭州三日游", null, USER, SESSION);

        assertEquals("CHAT", result.intent);
        assertEquals(1, l3Stub.calls.get());
    }

    // ==================== 验收标准 d：同会话追问 → L0 命中 ====================

    @Test
    @DisplayName("标准d：先发规划请求（L2 命中）再追问「那改成四天呢」→ cascade_hit=L0，零模型调用")
    void testCriterionD_followupHitsL0() {
        // 第一轮：规划请求走 L2，写回 L0
        router.classify("帮我规划杭州三日游", null, USER, SESSION);
        assertEquals(1, l2Stub.calls.get());

        // 第二轮：短追问（9 字符，含「那/改成/呢」）→ L0 沿用 PLANNING
        IntentResult followup = router.classify("那改成四天呢", null, USER, SESSION);

        assertNotNull(followup);
        assertEquals(IntentType.PLANNING, followup.toIntentType(), "L0 应沿用上一轮 PLANNING");
        assertEquals(1, l2Stub.calls.get(), "L0 命中后 L2 不应再被调用");
        assertEquals(0, l3Stub.calls.get());
    }

    @Test
    @DisplayName("L0 不触发：长消息（>20 字符）即使含代词也不查缓存")
    void testL0_notTriggeredByLongMessage() {
        router.classify("帮我规划杭州三日游", null, USER, SESSION);
        String longFollowup = "那改成四天呢，另外我们想在西湖边上找一家能看到日出的酒店，预算五百以内";
        assertTrue(longFollowup.trim().length() > 20);

        IntentResult result = router.classify(longFollowup, null, USER, SESSION);
        assertEquals(2, l2Stub.calls.get(), "长追问不走 L0，应再次调用 L2");
    }

    @Test
    @DisplayName("L0 不触发：短消息但无代词/语气词")
    void testL0_notTriggeredWithoutPronoun() {
        cache.saveRecentIntent(USER, SESSION, "PLANNING", Duration.ofMinutes(30));

        // 短但无代词/语气词：不含追问特征，不查 L0（此消息恰好也不命中 L1，会落 L2）
        router.classify("北京故宫门票", null, USER, SESSION);
        assertEquals(1, l2Stub.calls.get());
    }

    // ==================== 验收标准 e：L1 命中延迟 < 10ms ====================

    @Test
    @DisplayName("标准e：L1 命中平均延迟 < 10ms（循环 100 次）")
    void testCriterionE_l1LatencyUnder10ms() {
        // 预热（类加载/正则首次编译）
        for (int i = 0; i < 10; i++) {
            router.classify("你好", null, USER, SESSION);
        }
        int rounds = 100;
        long start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            router.classify("你好", null, USER, SESSION);
        }
        double avgMs = (System.nanoTime() - start) / 1_000_000.0 / rounds;
        assertTrue(avgMs < 10, "L1 平均延迟应 < 10ms，实测 " + avgMs + "ms");
    }

    // ==================== 上下文注入（2026-09-29 失忆修复 Fix 4） ====================

    @Test
    @DisplayName("上下文透传：contextBlock 原样到达 L2，且不写全局文本缓存")
    void testContext_passedToL2AndSkipsCacheWrite() {
        String ctx = "会话摘要：正在收集旅行需求\n最近对话：\n助手：请问您想去哪里玩？";
        IntentResult result = router.classify("长春", ctx, USER, SESSION);

        assertNotNull(result);
        assertEquals(IntentType.PLANNING, result.toIntentType());
        assertEquals(1, l2Stub.calls.get());
        assertEquals("长春", l2Stub.lastMessage);
        assertEquals(ctx, l2Stub.lastContext, "上下文应原样透传给 L2");
        assertNull(cache.getCachedL2("长春"), "带上下文的判定不应写全局文本缓存（防投毒）");
        assertEquals("PLANNING", cache.getRecentIntent(USER, SESSION), "L0 最近意图仍正常写回");
    }

    @Test
    @DisplayName("上下文旁路 L2 文本缓存：同文本带上下文两次调用均执行模型")
    void testContext_bypassesL2Cache() {
        String ctx = "助手：请问您想去哪里玩？";
        router.classify("长春", ctx, USER, SESSION);
        router.classify("长春", ctx, USER, SESSION);
        assertEquals(2, l2Stub.calls.get(), "带上下文不走文本缓存，第二次仍调用 L2");
    }

    @Test
    @DisplayName("无上下文时仍走 L2 文本缓存（旧行为回归）")
    void testNoContext_stillUsesL2Cache() {
        router.classify("我想去江南古镇玩几天", null, USER, SESSION);
        assertEquals(1, l2Stub.calls.get());
        router.classify("我想去江南古镇玩几天", null, USER, SESSION);
        assertEquals(1, l2Stub.calls.get(), "无上下文第二次相同文本应命中 L2_CACHE");
    }

    @Test
    @DisplayName("上下文不影响 L0/L1：打招呼走 L1、短追问走 L0，L2 零额外调用")
    void testContext_doesNotAffectL0L1() {
        // L1 部分：独立会话，避免「你好」写入的 CHAT 污染后续 L0 检查
        router.classify("你好", "任意上下文", USER, "conv-l1-part");
        assertEquals(0, l2Stub.calls.get(), "L1 命中不受上下文影响");

        // L0 部分：新会话（「帮我」是延续词会查 L0，recent 为空落 L2 写回 PLANNING）
        router.classify("帮我规划杭州三日游", null, USER, "conv-l0-part");
        assertEquals(1, l2Stub.calls.get());
        IntentResult followup = router.classify("那改成四天呢", "任意上下文", USER, "conv-l0-part");
        assertEquals(IntentType.PLANNING, followup.toIntentType());
        assertEquals(1, l2Stub.calls.get(), "L0 命中不受上下文影响");
    }

    @Test
    @DisplayName("L2 miss 时上下文透传到 L3 兜底")
    void testContext_passedToL3OnL2Miss() {
        l2Stub.next = null;
        String ctx = "助手：请问您想去哪里玩？";
        IntentResult result = router.classify("长春", ctx, USER, SESSION);

        assertEquals("CHAT", result.intent, "应返回 L3 结果");
        assertEquals(ctx, l3Stub.lastContext, "上下文应透传给 L3");
    }

    // ==================== 补充：L2 文本缓存 / Redis 降级 / 开关 / 默认规则 ====================

    @Test
    @DisplayName("L2 文本缓存：相同文本第二次直接命中缓存，L2 桩零新增调用")
    void testL2TextCacheHit() {
        router.classify("我想带我爸妈去江南的古镇玩几天，有什么推荐", null, USER, SESSION);
        assertEquals(1, l2Stub.calls.get());

        IntentResult second = router.classify("我想带我爸妈去江南的古镇玩几天，有什么推荐", null, USER, SESSION);
        assertEquals(1, l2Stub.calls.get(), "第二次相同文本应命中 L2_CACHE，不再调模型");
        assertNotNull(second);
    }

    @Test
    @DisplayName("Redis 全挂（缓存全抛异常）时级联照常：L1/L2/L3 链路不受影响")
    void testRedisDown_cascadeStillWorks() {
        IntentCascadeRouter downRouter = newRouter(new AppProperties(),
                new ThrowingIntentCache(), l2Stub, l3Stub);

        // L1 仍可用
        IntentResult l1Hit = downRouter.classify("你好", null, USER, SESSION);
        assertEquals(IntentType.CHAT, l1Hit.toIntentType());

        // L2 仍可用（缓存读写异常被吞）
        IntentResult l2Hit = downRouter.classify("帮我规划杭州三日游", null, USER, SESSION);
        assertEquals(IntentType.PLANNING, l2Hit.toIntentType());
        assertEquals(1, l2Stub.calls.get());

        // L3 兜底仍可用
        l2Stub.next = null;
        IntentResult l3Hit = downRouter.classify("安排一次周末的短途旅行", null, USER, SESSION);
        assertEquals("CHAT", l3Hit.intent);
        assertEquals(1, l3Stub.calls.get());
    }

    @Test
    @DisplayName("enabled=false 时直通 L3（保持旧行为），L1/L2 零调用")
    void testDisabled_bypassToL3() {
        AppProperties props = new AppProperties();
        props.getIntentCascade().setEnabled(false);
        IntentCascadeRouter offRouter = newRouter(props, cache, l2Stub, l3Stub);

        IntentResult result = offRouter.classify("你好", null, USER, SESSION);
        assertEquals("CHAT", result.intent, "应返回 L3 结果");
        assertEquals(0, l2Stub.calls.get());
        assertEquals(1, l3Stub.calls.get());
    }

    @Test
    @DisplayName("yml 未配置 L1 规则时代码内置默认规则生效")
    void testDefaultRules_whenYmlEmpty() {
        AppProperties props = new AppProperties(); // l1Rules 默认为空 List
        assertTrue(props.getIntentCascade().getL1Rules().isEmpty());
        IntentCascadeRouter defaultRouter = newRouter(props, cache, l2Stub, l3Stub);

        assertEquals(IntentType.CHAT, defaultRouter.classify("你好", null, USER, SESSION).toIntentType());
        assertEquals(IntentType.TOOL_CALL, defaultRouter.classify("/天气 北京", null, USER, SESSION).toIntentType());
        assertEquals(IntentType.PLANNING, defaultRouter.classify("/规划 杭州三日游", null, USER, SESSION).toIntentType());
    }

    @Test
    @DisplayName("yml 自定义规则生效：只配一条自定义规则时按它路由")
    void testCustomRules_fromYml() {
        AppProperties props = new AppProperties();
        L1Rule custom = new L1Rule();
        custom.setPattern("^\\/vip$");
        custom.setIntent("CHAT");
        props.getIntentCascade().getL1Rules().add(custom);
        IntentCascadeRouter customRouter = newRouter(props, cache, l2Stub, l3Stub);

        // 自定义规则命中
        assertEquals(IntentType.CHAT, customRouter.classify("/vip", null, USER, SESSION).toIntentType());
        // 内置默认不再生效（yml 非空时完全以 yml 为准）：「你好」落 L2
        IntentResult hello = customRouter.classify("你好", null, USER, SESSION);
        assertEquals(1, l2Stub.calls.get(), "yml 只配了 /vip，「你好」应落 L2");
    }

    @Test
    @DisplayName("非法 L1 规则被跳过不阻断（坏正则 + 坏标签）")
    void testInvalidRules_skipped() {
        AppProperties props = new AppProperties();
        L1Rule badPattern = new L1Rule();
        badPattern.setPattern("[unclosed");
        badPattern.setIntent("CHAT");
        L1Rule badIntent = new L1Rule();
        badIntent.setPattern("^你好$");
        badIntent.setIntent("NOT_A_LABEL");
        props.getIntentCascade().getL1Rules().add(badPattern);
        props.getIntentCascade().getL1Rules().add(badIntent);
        props.getIntentCascade().getL1Rules().add(rule("^早$", "CHAT"));
        IntentCascadeRouter robustRouter = newRouter(props, cache, l2Stub, l3Stub);

        // 坏规则跳过后好规则仍生效
        assertEquals(IntentType.CHAT, robustRouter.classify("早", null, USER, SESSION).toIntentType());
        // 坏正则那条没拦住任何东西
        assertEquals(0, l2Stub.calls.get());
    }

    @Test
    @DisplayName("全层未命中（L2/L3 均 null）→ cascade_miss，返回 null 不抛异常")
    void testAllMiss_returnsNull() {
        l2Stub.next = null;
        l3Stub.next = null;
        IntentResult result = router.classify("安排一次旅行", null, USER, SESSION);
        assertNull(result, "全 miss 时返回 null，ChatService 保持既有容错");
    }

    private static L1Rule rule(String pattern, String intent) {
        L1Rule r = new L1Rule();
        r.setPattern(pattern);
        r.setIntent(intent);
        return r;
    }
}
