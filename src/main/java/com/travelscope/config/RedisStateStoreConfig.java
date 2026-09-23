package com.travelscope.config;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Set;

/**
 * Agent 会话状态的 Redis 存储配置（2026-09-23 并发改造）
 * <p>
 * master Agent 的 stateStore 由 InMemoryAgentStateStore（无 TTL 无上限，2000 用户下
 * 单机堆内存无限增长）换为 RedisAgentStateStore（agentscope-extensions-redis 已在 pom）：
 * 会话状态不占应用堆、进程重启不失忆（顺带修复重启丢上下文）。
 * </p>
 * <p>
 * 独立 Lettuce RedisClient（与 spring-data-redis 的连接池隔离，互不干扰）；
 * Redis 不可用时降级 InMemory（保持启动韧性——与项目其它 Redis 组件的降级风格一致）。
 * planner 工厂内每次新建的 InMemory 保持不变（随 spawn 生命周期，无泄漏）。
 * </p>
 * <p>
 * 框架 store API 无 TTL——由 {@link #cleanupStaleAgentStateKeys()} 每小时 SCAN
 * 清理 7 天未活跃的 {@code ts:agent:*} 键（框架写入时带 updatedAt 元数据字段；
 * 扫描侧按 Redis OBJECT IDLETIME 近似判断，避免解析每个键的值）。
 * </p>
 */
@Configuration
public class RedisStateStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisStateStoreConfig.class);

    /** master 会话状态的 Redis key 前缀（清理任务按此前缀扫描） */
    public static final String AGENT_STATE_PREFIX = "ts:agent:";

    /** 键空闲多久视为过期删除（7 天未活跃会话） */
    private static final Duration STATE_IDLE_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;

    public RedisStateStoreConfig(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * master 的 Agent 会话状态存储：优先 RedisAgentStateStore，不可用降级 InMemory。
     * <p>Redis 连接参数复用 spring.data.redis（Spring Boot RedisProperties 自动绑定）。</p>
     */
    @Bean
    public AgentStateStore travelAgentStateStore(
            org.springframework.boot.autoconfigure.data.redis.RedisProperties redisProperties) {
        try {
            RedisURI.Builder uriBuilder = RedisURI.builder()
                    .withHost(redisProperties.getHost())
                    .withPort(redisProperties.getPort())
                    .withDatabase(redisProperties.getDatabase())
                    .withTimeout(Duration.ofSeconds(5));
            if (redisProperties.getPassword() != null) {
                uriBuilder.withPassword(redisProperties.getPassword().toCharArray());
            }
            RedisClient client = RedisClient.create(uriBuilder.build());
            // 连接探活：不可用立即降级（不抛到启动）
            try (StatefulRedisConnection<String, String> probe = client.connect()) {
                probe.sync().ping();
            }
            log.info("Agent 会话状态存储: RedisAgentStateStore（prefix={}, host={}:{}, 降级 InMemory 已备）",
                    AGENT_STATE_PREFIX, redisProperties.getHost(), redisProperties.getPort());
            return io.agentscope.extensions.redis.state.RedisAgentStateStore.builder()
                    .lettuceClient(client)
                    .keyPrefix(AGENT_STATE_PREFIX)
                    .build();
        } catch (Exception e) {
            log.warn("Redis 状态存储不可用，master 降级 InMemoryAgentStateStore（重启失忆 + 堆内存存储）: {}",
                    e.getMessage());
            return new InMemoryAgentStateStore();
        }
    }

    /**
     * 清理 7 天未活跃的 Agent 状态键（每小时）：框架 store API 无 TTL，外层定时清理。
     * <p>OBJECT IDLETIME 是近似值（读写会刷新），对「读=活跃」的语义正好成立。</p>
     */
    @Scheduled(fixedDelay = 3600_000, initialDelay = 3600_000)
    public void cleanupStaleAgentStateKeys() {
        try {
            Set<String> keys = redisTemplate.keys(AGENT_STATE_PREFIX + "*");
            if (keys == null || keys.isEmpty()) {
                return;
            }
            long idleThresholdSeconds = STATE_IDLE_TTL.toSeconds();
            int removed = 0;
            for (String key : keys) {
                Duration idle = redisTemplate.execute(
                        (org.springframework.data.redis.core.RedisCallback<Duration>) connection ->
                                connection.keyCommands().idletime(
                                        key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                if (idle != null && idle.toSeconds() >= idleThresholdSeconds) {
                    redisTemplate.delete(key);
                    removed++;
                }
            }
            if (removed > 0) {
                log.info("Agent 状态键清理: 扫描 {} 个，删除 {} 个（{} 天未活跃）",
                        keys.size(), removed, STATE_IDLE_TTL.toDays());
            }
        } catch (Exception e) {
            log.debug("Agent 状态键清理失败（Redis 不可用则状态本就未上 Redis）: {}", e.getMessage());
        }
    }
}
