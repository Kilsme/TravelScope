package com.travelscope.service;

import com.travelscope.service.TaskResultCache.TaskType;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TaskResultCache 测试（FR-S14：内存降级单测 + Redis 6379 门控回环）
 */
class TaskResultCacheTest {

    /** opsForValue/set/get 全抛异常 → 内存降级路径 */
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

    // ==================== 内存降级单测（无门控必跑） ====================

    @Test
    @DisplayName("降级路径：register/get 回环（Redis 全挂时不阻断，内存兜底）")
    void testMemoryFallbackRoundTrip() {
        TaskResultCache cache = new TaskResultCache(new ThrowingRedisTemplate());
        cache.register("u1", "conv-1", TaskType.POI, "T3", "# 景点候选清单\n西湖...");

        String cached = cache.get("u1", "conv-1", TaskType.POI);
        assertNotNull(cached, "降级路径 get 应命中内存缓存");
        assertTrue(cached.startsWith("# 景点候选清单"), "get 应剥离元信息头: " + cached);
        assertTrue(cache.has("u1", "conv-1", TaskType.POI));
    }

    @Test
    @DisplayName("TaskType 宽容解析：poi/poi_shortlist/route/weather 等别名")
    void testTaskTypeParsing() {
        assertEquals(TaskType.POI, TaskType.of("poi"));
        assertEquals(TaskType.POI, TaskType.of("poi_shortlist"));
        assertEquals(TaskType.ROUTE, TaskType.of("route_plan"));
        assertEquals(TaskType.WEATHER, TaskType.of("Weather"));
        assertNull(TaskType.of("unknown"));
        assertNull(TaskType.of(null));
    }

    @Test
    @DisplayName("TTL 按类型：天气 10min，POI/路线/酒店 30min")
    void testTtlByType() {
        assertEquals(Duration.ofMinutes(10), TaskType.WEATHER.ttl);
        assertEquals(Duration.ofMinutes(30), TaskType.POI.ttl);
        assertEquals(Duration.ofMinutes(30), TaskType.ROUTE.ttl);
        assertEquals(Duration.ofMinutes(30), TaskType.HOTEL.ttl);
    }

    @Test
    @DisplayName("invalidate 单段/全失效（内存降级路径）")
    void testInvalidate() {
        TaskResultCache cache = new TaskResultCache(new ThrowingRedisTemplate());
        cache.register("u1", "conv-1", TaskType.POI, "T3", "poi 内容");
        cache.register("u1", "conv-1", TaskType.ROUTE, "T4", "route 内容");
        cache.invalidate("u1", "conv-1", TaskType.POI);
        assertNull(cache.get("u1", "conv-1", TaskType.POI));
        assertNotNull(cache.get("u1", "conv-1", TaskType.ROUTE), "单段失效不影响其他段");
        cache.invalidate("u1", "conv-1", null);
        assertNull(cache.get("u1", "conv-1", TaskType.ROUTE));
    }

    @Test
    @DisplayName("会话隔离：不同会话缓存互不可见")
    void testIsolation() {
        TaskResultCache cache = new TaskResultCache(new ThrowingRedisTemplate());
        cache.register("u1", "conv-a", TaskType.POI, "T3", "会话A的POI");
        assertNull(cache.get("u1", "conv-b", TaskType.POI));
    }

    // ==================== Redis 真实回环（6379 门控） ====================

    static boolean redisReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 6379), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @org.junit.jupiter.api.Nested
    @EnabledIf(value = "com.travelscope.service.TaskResultCacheTest#redisReachable",
            disabledReason = "本机 6379 无 Redis 监听（docker start travelscope-redis 后重跑）")
    class RedisRoundTrip {

        private StringRedisTemplate redisTemplate() {
            RedisStandaloneConfiguration conf = new RedisStandaloneConfiguration("localhost", 6379);
            conf.setPassword(org.springframework.data.redis.connection.RedisPassword.of("root123456"));
            LettuceConnectionFactory factory = new LettuceConnectionFactory(conf);
            factory.afterPropertiesSet();
            return new StringRedisTemplate(factory);
        }

        @Test
        @DisplayName("Redis 回环：register → key 结构与 TTL → get 剥离元信息头 → cache_hit 语义")
        void testRedisRoundTrip() throws Exception {
            StringRedisTemplate rt = redisTemplate();
            TaskResultCache cache = new TaskResultCache(rt);

            cache.register("rt", "conv-r1", TaskType.POI, "T3", "POI清单内容");
            String key = "taskresult:rt:conv-r1:poi";
            assertNotNull(rt.opsForValue().get(key), "Redis 中应有该键");
            assertTrue(rt.opsForValue().get(key).contains("# task_id=T3"), "元信息头应含 task_id");
            assertTrue(rt.getExpire(key) > 0, "应有 TTL");

            String cached = cache.get("rt", "conv-r1", TaskType.POI);
            assertEquals("POI清单内容", cached, "get 应返回剥离头的内容");

            rt.delete(key);
        }

        @Test
        @DisplayName("天气 TTL 10min 与 POI 30min 差异落 Redis")
        void testTtlDifference() {
            StringRedisTemplate rt = redisTemplate();
            TaskResultCache cache = new TaskResultCache(rt);
            cache.register("rt", "conv-r2", TaskType.WEATHER, "T1", "晴 25℃");
            cache.register("rt", "conv-r2", TaskType.POI, "T3", "POI");

            Long weatherTtl = rt.getExpire("taskresult:rt:conv-r2:weather");
            Long poiTtl = rt.getExpire("taskresult:rt:conv-r2:poi");
            assertTrue(weatherTtl > 0 && weatherTtl <= 600, "天气 TTL ≤ 600s: " + weatherTtl);
            assertTrue(poiTtl > 600, "POI TTL > 600s: " + poiTtl);

            rt.delete("taskresult:rt:conv-r2:weather");
            rt.delete("taskresult:rt:conv-r2:poi");
        }
    }
}
