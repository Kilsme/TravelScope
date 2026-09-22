package com.travelscope.service;

import com.travelscope.dto.TripRequirementState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TripRequirementStore 真实 Redis 集成测试（FR-S02：状态存 Redis hash）
 * <p>
 * 自定义门控：本机 6379 可达才跑（与 RedisIntentCacheTest 同款）。
 * 手工构造 Lettuce 连接（localhost:6379/root123456），不走 Spring 上下文。
 * </p>
 */
@EnabledIf(value = "com.travelscope.service.TripRequirementStoreRedisTest#redisReachable",
        disabledReason = "本机 6379 无 Redis 监听（docker start travelscope-redis 后重跑）")
class TripRequirementStoreRedisTest {

    private static TripRequirementStore store;
    private static StringRedisTemplate redisTemplate;

    static boolean redisReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 6379), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    static void setUp() {
        RedisStandaloneConfiguration conf = new RedisStandaloneConfiguration("localhost", 6379);
        conf.setPassword(org.springframework.data.redis.connection.RedisPassword.of("root123456"));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(conf);
        factory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(factory);
        store = new TripRequirementStore(redisTemplate);
    }

    private static String key(String userId, String sessionId) {
        return TripRequirementStore.KEY_PREFIX + userId + ":" + sessionId;
    }

    @Test
    @DisplayName("检测标准2：hash 字段级读写回环——HGETALL 直接可见每个需求字段")
    void testHashRoundTrip_visibleFields() {
        TripRequirementState s = store.get("rt", "conv-1");
        s.destination = "杭州";
        s.days = 3;
        s.startDate = "下周末";
        s.fromCity = "上海";
        s.budget = "穷游（低预算）";
        s.people = 3;
        store.save("rt", "conv-1", s);

        // 直接从 Redis HGETALL 验证 hash 结构（运维视角可见）
        Map<Object, Object> hash = redisTemplate.opsForHash().entries(key("rt", "conv-1"));
        assertEquals("杭州", hash.get("destination"));
        assertEquals("3", hash.get("days"));
        assertEquals("下周末", hash.get("startDate"));
        assertEquals("上海", hash.get("fromCity"));
        assertEquals("穷游（低预算）", hash.get("budget"));
        assertEquals("3", hash.get("people"));
        assertEquals("COLLECTING", hash.get("status"));

        // 反序列化回环一致
        TripRequirementState back = store.get("rt", "conv-1");
        assertEquals("杭州", back.destination);
        assertEquals(3, back.days);
        assertEquals(3, back.people);
        assertEquals("穷游（低预算）", back.budget);

        redisTemplate.delete(key("rt", "conv-1"));
    }

    @Test
    @DisplayName("null 字段不落 hash（保持精简），读回为 null")
    void testNullFieldsOmitted() {
        TripRequirementState s = new TripRequirementState();
        s.destination = "成都";
        store.save("rt", "conv-2", s);

        Map<Object, Object> hash = redisTemplate.opsForHash().entries(key("rt", "conv-2"));
        assertNull(hash.get("days"), "未填字段不应出现在 hash 里");
        assertEquals("成都", hash.get("destination"));

        redisTemplate.delete(key("rt", "conv-2"));
    }

    @Test
    @DisplayName("TTL 存在（24h 滚动刷新）")
    void testTtlSet() {
        TripRequirementState s = new TripRequirementState();
        s.destination = "西安";
        store.save("rt", "conv-3", s);

        Long ttl = redisTemplate.getExpire(key("rt", "conv-3"));
        assertNotNull(ttl);
        assertTrue(ttl > 0, "保存后应有 TTL: " + ttl);

        redisTemplate.delete(key("rt", "conv-3"));
    }

    @Test
    @DisplayName("反问轮次与 DEGRADED 跨读写持久（ask_user 3 轮 → DEGRADED 落 Redis）")
    void testClarifyCyclesPersisted() {
        for (int i = 1; i <= 3; i++) {
            store.incrementClarifyCycles("rt", "conv-4");
        }
        // 模拟重启：重新从 Redis 读
        TripRequirementState s = store.get("rt", "conv-4");
        assertEquals(3, s.clarifyCycles, "轮次应跨读取持久");
        assertEquals(TripRequirementState.Status.DEGRADED, s.status, "3 轮仍缺应 DEGRADED");
        assertEquals("DEGRADED", redisTemplate.opsForHash().entries(key("rt", "conv-4")).get("status"));

        redisTemplate.delete(key("rt", "conv-4"));
    }

    @Test
    @DisplayName("DEGRADED 不回退：收齐必填后状态保持 DEGRADED（放行决策已做出）")
    void testDegradedSticky() {
        for (int i = 0; i < 3; i++) {
            store.incrementClarifyCycles("rt", "conv-5");
        }
        TripRequirementState s = store.get("rt", "conv-5");
        s.destination = "杭州";
        s.days = 3;
        s.startDate = "周六";
        s.fromCity = "上海";
        // 模拟 update_requirement_state 的置位逻辑（COLLECTING 才升 DONE）
        if (s.missingFields().isEmpty() && s.status == TripRequirementState.Status.COLLECTING) {
            s.status = TripRequirementState.Status.DONE;
        }
        store.save("rt", "conv-5", s);

        assertEquals(TripRequirementState.Status.DEGRADED, store.get("rt", "conv-5").status);
        redisTemplate.delete(key("rt", "conv-5"));
    }

    @Test
    @DisplayName("会话隔离：键含 userId+sessionId，互不串")
    void testKeyIsolation() {
        TripRequirementState a = new TripRequirementState();
        a.destination = "杭州";
        store.save("u-a", "conv-6", a);

        TripRequirementState b = store.get("u-b", "conv-6");
        assertNull(b.destination, "不同用户读不到");
        TripRequirementState c = store.get("u-a", "conv-7");
        assertNull(c.destination, "同用户不同会话读不到");

        redisTemplate.delete(key("u-a", "conv-6"));
    }
}
