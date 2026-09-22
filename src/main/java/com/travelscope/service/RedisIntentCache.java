package com.travelscope.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

/**
 * 意图级联缓存 Redis 实现（FR-S01）
 * <p>
 * 键设计：
 * <ul>
 *   <li>L0 最近意图：{@code travelscope:intent:l0:{userId}:{sessionId}}，TTL 30 分钟，
 *       每层命中后刷新（会话延续窗口滚动）</li>
 *   <li>L2 文本缓存：{@code travelscope:intent:l2:{sha256hex(trim(消息))}}，
 *       相同文本直接命中，跳过模型调用</li>
 * </ul>
 * <p>
 * 降级策略：所有操作 try/catch 全部异常——首次失败 log.warn 提示，后续失败降为
 * log.debug（避免 Redis 长期不可用时刷屏），操作表现为返回 null / no-op，
 * 级联自动退化为 L1 → L2 → L3。
 * </p>
 */
@Service
public class RedisIntentCache implements IntentCache {

    private static final Logger log = LoggerFactory.getLogger(RedisIntentCache.class);

    private static final String L0_KEY_PREFIX = "travelscope:intent:l0:";
    private static final String L2_KEY_PREFIX = "travelscope:intent:l2:";

    private final StringRedisTemplate redisTemplate;

    /** Redis 故障标记：首次失败 warn，之后降级为 debug */
    private volatile boolean redisFailureLogged = false;

    public RedisIntentCache(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public String getRecentIntent(String userId, String sessionId) {
        try {
            return redisTemplate.opsForValue().get(l0Key(userId, sessionId));
        } catch (Exception e) {
            logRedisFailure("getRecentIntent", e);
            return null;
        }
    }

    @Override
    public void saveRecentIntent(String userId, String sessionId, String intent, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(l0Key(userId, sessionId), intent, ttl);
        } catch (Exception e) {
            logRedisFailure("saveRecentIntent", e);
        }
    }

    @Override
    public String getCachedL2(String normalizedMessage) {
        try {
            return redisTemplate.opsForValue().get(l2Key(normalizedMessage));
        } catch (Exception e) {
            logRedisFailure("getCachedL2", e);
            return null;
        }
    }

    @Override
    public void saveCachedL2(String normalizedMessage, String intent, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(l2Key(normalizedMessage), intent, ttl);
        } catch (Exception e) {
            logRedisFailure("saveCachedL2", e);
        }
    }

    private String l0Key(String userId, String sessionId) {
        return L0_KEY_PREFIX + userId + ":" + sessionId;
    }

    private String l2Key(String normalizedMessage) {
        return L2_KEY_PREFIX + sha256Hex(normalizedMessage);
    }

    /**
     * SHA-256 十六进制摘要（L2 缓存键；消息原文不做键，避免超长与特殊字符）
     */
    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必带 SHA-256，理论不可达；退化用 hashCode 保证缓存仍可用
            return Integer.toHexString(text.hashCode());
        }
    }

    /**
     * Redis 故障日志：首次 warn（提示定位），后续 debug（避免刷屏），均不抛出
     */
    private void logRedisFailure(String operation, Exception e) {
        if (redisFailureLogged) {
            log.debug("Redis 缓存操作失败（已降级，级联退化为直查）: op={}, 原因: {}", operation, e.getMessage());
        } else {
            redisFailureLogged = true;
            log.warn("Redis 缓存首次操作失败，意图级联降级为无缓存模式（L1→L2→L3）: op={}, 原因: {}",
                    operation, e.getMessage());
        }
    }
}
