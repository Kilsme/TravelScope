package com.travelscope.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * RedisIntentCache 真实 Redis 回环测试
 * <p>
 * 自定义门控：本机 6379 端口可达才跑（Redis 未启动时整类跳过，CI 无 Redis 也不挂）。
 * 手工构造 LettuceConnectionFactory（localhost:6379/root123456，与 application.yml
 * 和 docker-compose.yml 一致），不走 Spring 上下文。
 * </p>
 */
@EnabledIf(value = "com.travelscope.service.RedisIntentCacheTest#redisReachable",
        disabledReason = "本机 6379 无 Redis 监听（docker-compose up -d redis 后重跑）")
class RedisIntentCacheTest {

    private static RedisIntentCache cache;
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
        cache = new RedisIntentCache(redisTemplate);
    }

    @Test
    @DisplayName("L0 回环：写回 → 读取一致；键隔离（不同会话互不串）")
    void testL0_roundTrip_andIsolation() {
        cache.saveRecentIntent("u1", "conv-a", "PLANNING", Duration.ofSeconds(60));
        assertEquals("PLANNING", cache.getRecentIntent("u1", "conv-a"));

        // 会话隔离：另一会话读不到
        assertNull(cache.getRecentIntent("u1", "conv-b"));
        // 用户隔离：另一用户读不到
        assertNull(cache.getRecentIntent("u2", "conv-a"));

        // 清理（避免残留影响后续用例）
        redisTemplate.delete("travelscope:intent:l0:u1:conv-a");
    }

    @Test
    @DisplayName("L2 回环：相同文本命中缓存；不同文本不串")
    void testL2_roundTrip() {
        cache.saveCachedL2("帮我规划杭州三日游", "PLANNING", Duration.ofSeconds(60));
        assertEquals("PLANNING", cache.getCachedL2("帮我规划杭州三日游"));
        assertNull(cache.getCachedL2("完全不同的一条消息"));

        // 清理
        redisTemplate.delete("travelscope:intent:l2:"
                + sha256("帮我规划杭州三日游"));
    }

    @Test
    @DisplayName("TTL 过期后读取为 null（1 秒 TTL 实测）")
    void testTtl_expiry() throws InterruptedException {
        cache.saveRecentIntent("u1", "conv-ttl", "CHAT", Duration.ofMillis(800));
        assertEquals("CHAT", cache.getRecentIntent("u1", "conv-ttl"));
        Thread.sleep(1200);
        assertNull(cache.getRecentIntent("u1", "conv-ttl"));
    }

    private static String sha256(String text) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
