package com.travelscope.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务结果缓存（FR-S14：为 P7 局部回炉打底）
 * <p>
 * 各子任务产出（poi_shortlist / route_plan / itinerary_draft / weather / hotel）登记进 Redis，
 * 二次规划（需求未变）或 Reviewer 回炉时命中即复用，跳过对应子 Agent 重跑。
 * </p>
 * <p>
 * Key：{@code taskresult:{userId}:{sessionId}:{taskType}}（v3 需求文档规范）；
 * Value：产出内容 + 头部元信息行（task_id/登记时间）。TTL 按类型：
 * POI 30min / 路线 30min / 酒店 30min / 天气 10min（天气时效短）。
 * 命中打 {@code cache_hit={taskType}} 日志（检测标准 5 锚点）。
 * </p>
 * <p>
 * 降级：Redis 异常回退进程内存（首次 warn 后续 debug，同 TripRequirementStore 风格）；
 * invalidate 预留给 P7 按影响面失效（目的地变更全失效 / 预算变更只失效酒店段）。
 * </p>
 */
public class TaskResultCache {

    private static final Logger log = LoggerFactory.getLogger(TaskResultCache.class);

    static final String KEY_PREFIX = "taskresult:";

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 支持的任务产出类型与 TTL（FR-S14：天气时效短 10min，其余 30min） */
    public enum TaskType {
        POI(Duration.ofMinutes(30)),
        ROUTE(Duration.ofMinutes(30)),
        HOTEL(Duration.ofMinutes(30)),
        WEATHER(Duration.ofMinutes(10)),
        ITINERARY(Duration.ofMinutes(30));

        public final Duration ttl;

        TaskType(Duration ttl) {
            this.ttl = ttl;
        }

        /** 宽容解析（工具入参小写 poi/route/hotel/weather/itinerary） */
        public static TaskType of(String label) {
            if (label == null) {
                return null;
            }
            return switch (label.trim().toLowerCase()) {
                case "poi", "poi_shortlist" -> POI;
                case "route", "route_plan" -> ROUTE;
                case "hotel", "hotel_search" -> HOTEL;
                case "weather", "weather_query" -> WEATHER;
                case "itinerary", "itinerary_draft" -> ITINERARY;
                default -> null;
            };
        }
    }

    private final StringRedisTemplate redisTemplate;

    /** 降级内存存储 */
    private final Map<String, String> memoryFallback = new ConcurrentHashMap<>();

    private volatile boolean redisFailureLogged = false;

    public TaskResultCache(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 登记任务产出（planner/poi/route 等产出文件后调用）
     *
     * @param taskId 关联任务 ID（如 T3，可为空）
     */
    public void register(String userId, String sessionId, TaskType type, String taskId, String content) {
        String key = containerKey(userId, sessionId, type);
        String value = "# task_id=" + (taskId != null ? taskId : "-")
                + " # registered_at=" + LocalDateTime.now().format(FMT) + "\n" + content;
        try {
            redisTemplate.opsForValue().set(key, value, type.ttl);
        } catch (Exception e) {
            logRedisFailure("register", e);
            memoryFallback.put(key, value);
        }
        log.info("task_result_registered type={} taskId={} 用户={}, 会话={}",
                type, taskId, userId, sessionId);
    }

    /**
     * 查询缓存（二次规划/回炉前调用）：命中打 cache_hit 日志并返回内容（剥离元信息头）；
     * 未命中返回 null
     */
    public String get(String userId, String sessionId, TaskType type) {
        String key = containerKey(userId, sessionId, type);
        String value;
        try {
            value = redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            logRedisFailure("get", e);
            value = memoryFallback.get(key);
        }
        if (value == null) {
            return null;
        }
        log.info("cache_hit={} 用户={}, 会话={}", type.name().toLowerCase(), userId, sessionId);
        return stripMetaHeader(value);
    }

    /**
     * 是否命中（不打 cache_hit 日志的静默版，预检用）
     */
    public boolean has(String userId, String sessionId, TaskType type) {
        String key = containerKey(userId, sessionId, type);
        try {
            return redisTemplate.hasKey(key);
        } catch (Exception e) {
            logRedisFailure("has", e);
            return memoryFallback.containsKey(key);
        }
    }

    /**
     * 失效（P7 按影响面：单段 or 全部）
     */
    public void invalidate(String userId, String sessionId, TaskType type) {
        if (type == null) {
            // 全失效
            for (TaskType t : TaskType.values()) {
                invalidateOne(userId, sessionId, t);
            }
            return;
        }
        invalidateOne(userId, sessionId, type);
    }

    private void invalidateOne(String userId, String sessionId, TaskType type) {
        String key = containerKey(userId, sessionId, type);
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            logRedisFailure("invalidate", e);
        }
        memoryFallback.remove(key);
    }

    private static String containerKey(String userId, String sessionId, TaskType type) {
        return KEY_PREFIX + userId + ":" + sessionId + ":" + type.name().toLowerCase();
    }

    /** 剥离 register 时写入的元信息头行（# task_id=… # registered_at=…） */
    private static String stripMetaHeader(String value) {
        int firstNewline = value.indexOf('\n');
        return firstNewline >= 0 && firstNewline < value.length() - 1
                ? value.substring(firstNewline + 1) : value;
    }

    private void logRedisFailure(String operation, Exception e) {
        if (redisFailureLogged) {
            log.debug("任务结果缓存 Redis 操作失败（已降级内存）: op={}, 原因: {}", operation, e.getMessage());
        } else {
            redisFailureLogged = true;
            log.warn("任务结果缓存 Redis 首次操作失败，降级为内存存储: op={}, 原因: {}",
                    operation, e.getMessage());
        }
    }
}
