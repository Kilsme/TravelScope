package com.travelscope.service;

import com.travelscope.dto.TripRequirementState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 行程需求状态仓库（v3 需求文档 3.3 / FR-S02）
 * <p>
 * TripRequirementState 状态机的存储层，注册键 {@code userId:sessionId} 双键隔离
 * （对齐 TaskRegistry 模式），intake-agent 每轮被委派时经 RequirementTools 读写。
 * 状态跨 spawn 持久，多轮反问之间不丢失。
 * </p>
 * <p>
 * <b>Redis hash 存储</b>：{@code trip:req:{userId}:{sessionId}}，hash 字段即状态字段
 * （destination/days/startDate/fromCity/budget/preference/people/special/status/clarifyCycles），
 * 每次写入刷新 TTL（24 小时，防会话键无限累积）——运维可直接 HGETALL 观察需求收集进度。
 * </p>
 * <p>
 * <b>降级</b>：Redis 异常时回退进程内存 ConcurrentHashMap（首次失败 warn、后续 debug，
 * 与 RedisIntentCache 同款模式）——降级期间状态仅存本进程，重启即失，但不阻断反问链路。
 * </p>
 * <p>
 * 本类不使用 @Component/@Service，由 AgentConfig 以 Bean 注册（与 TaskRegistry 一致）。
 * </p>
 */
public class TripRequirementStore {

    private static final Logger log = LoggerFactory.getLogger(TripRequirementStore.class);

    /** 反问轮次上限（FR-S02：3 轮仍缺则 DEGRADED 放行） */
    public static final int MAX_CLARIFY_CYCLES = 3;

    /** Redis 键前缀：trip:req:{userId}:{sessionId} */
    static final String KEY_PREFIX = "trip:req:";

    /** 会话状态 TTL（24 小时，每次写入滚动刷新） */
    private static final java.time.Duration STATE_TTL = java.time.Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    /** 降级内存存储：userId:sessionId → 状态机 */
    private final Map<String, TripRequirementState> memoryFallback = new ConcurrentHashMap<>();

    /** Redis 故障标记：首次失败 warn，之后降为 debug */
    private volatile boolean redisFailureLogged = false;

    public TripRequirementStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 取该会话的状态机（无则新建，首次进入 COLLECTING）
     * <p>
     * 注意：Redis 模式下返回的是<b>反序列化副本</b>，直接修改字段不会持久——
     * 修改后必须调 {@link #save} 写回。
     * </p>
     */
    public TripRequirementState get(String userId, String sessionId) {
        String key = containerKey(userId, sessionId);
        try {
            Map<Object, Object> hash = redisTemplate.opsForHash().entries(key);
            if (hash == null || hash.isEmpty()) {
                return new TripRequirementState();
            }
            return fromHash(hash);
        } catch (Exception e) {
            logRedisFailure("get", e);
            return memoryFallback.computeIfAbsent(key,
                    k -> {
                        log.info("需求状态机初始化(内存降级): 用户={}, 会话={}", userId, sessionId);
                        return new TripRequirementState();
                    });
        }
    }

    /**
     * 写回状态机（Redis hash 逐字段 + TTL 刷新；降级时写内存）
     */
    public void save(String userId, String sessionId, TripRequirementState state) {
        String key = containerKey(userId, sessionId);
        try {
            Map<String, String> hash = toHash(state);
            redisTemplate.opsForHash().putAll(key, hash);
            redisTemplate.expire(key, STATE_TTL);
        } catch (Exception e) {
            logRedisFailure("save", e);
            memoryFallback.put(key, state);
        }
    }

    /**
     * 判断必填项是否已收齐（DONE 或 DEGRADED 均视为可放行进入规划）
     */
    public boolean isCollected(String userId, String sessionId) {
        TripRequirementState s = get(userId, sessionId);
        return s.missingFields().isEmpty() || s.status != TripRequirementState.Status.COLLECTING;
    }

    /**
     * 反问轮次 +1（ask_user 工具调用时触发）：
     * 达到上限且必填仍缺 → 置 DEGRADED（带默认值放行，intake-agent 收尾时标注「待确认」）
     */
    public TripRequirementState incrementClarifyCycles(String userId, String sessionId) {
        TripRequirementState s = get(userId, sessionId);
        s.clarifyCycles++;
        if (s.clarifyCycles >= MAX_CLARIFY_CYCLES && !s.missingFields().isEmpty()
                && s.status == TripRequirementState.Status.COLLECTING) {
            s.status = TripRequirementState.Status.DEGRADED;
            log.info("需求收集 DEGRADED 放行: 用户={}, 会话={}, 已反问 {} 轮仍缺 {}",
                    userId, sessionId, s.clarifyCycles, s.missingFields());
        }
        save(userId, sessionId, s);
        return s;
    }

    /**
     * 清除该会话的状态（需求收齐、规划完成后可清理；预留，暂无调用方）
     */
    public void clear(String userId, String sessionId) {
        String key = containerKey(userId, sessionId);
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            logRedisFailure("clear", e);
        }
        memoryFallback.remove(key);
    }

    // ==================== hash 序列化 ====================

    private static String containerKey(String userId, String sessionId) {
        return KEY_PREFIX + userId + ":" + sessionId;
    }

    /** 状态机 → hash 字段（null 字段跳过，保持 hash 精简） */
    private static Map<String, String> toHash(TripRequirementState s) {
        Map<String, String> hash = new HashMap<>();
        if (s.destination != null) {
            hash.put("destination", s.destination);
        }
        if (s.days != null) {
            hash.put("days", String.valueOf(s.days));
        }
        if (s.startDate != null) {
            hash.put("startDate", s.startDate);
        }
        if (s.fromCity != null) {
            hash.put("fromCity", s.fromCity);
        }
        if (s.budget != null) {
            hash.put("budget", s.budget);
        }
        if (s.preference != null) {
            hash.put("preference", s.preference);
        }
        if (s.people != null) {
            hash.put("people", String.valueOf(s.people));
        }
        if (s.special != null) {
            hash.put("special", s.special);
        }
        hash.put("status", s.status.name());
        hash.put("clarifyCycles", String.valueOf(s.clarifyCycles));
        return hash;
    }

    /** hash 字段 → 状态机（缺字段按默认值；非法值容忍跳过） */
    private static TripRequirementState fromHash(Map<Object, Object> hash) {
        TripRequirementState s = new TripRequirementState();
        s.destination = str(hash.get("destination"));
        s.days = positiveInt(str(hash.get("days")));
        s.startDate = str(hash.get("startDate"));
        s.fromCity = str(hash.get("fromCity"));
        s.budget = str(hash.get("budget"));
        s.preference = str(hash.get("preference"));
        s.people = positiveInt(str(hash.get("people")));
        s.special = str(hash.get("special"));
        try {
            s.status = TripRequirementState.Status.valueOf(str(hash.get("status")));
        } catch (Exception e) {
            s.status = TripRequirementState.Status.COLLECTING;
        }
        Integer cycles = positiveInt(str(hash.get("clarifyCycles")));
        s.clarifyCycles = cycles != null ? cycles : 0;
        return s;
    }

    private static String str(Object v) {
        return v != null && !String.valueOf(v).isBlank() ? String.valueOf(v) : null;
    }

    private static Integer positiveInt(String v) {
        if (v == null) {
            return null;
        }
        try {
            int i = Integer.parseInt(v.trim());
            return i > 0 ? i : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Redis 故障日志：首次 warn（提示定位），后续 debug（避免刷屏），均不抛出
     */
    private void logRedisFailure(String operation, Exception e) {
        if (redisFailureLogged) {
            log.debug("需求状态 Redis 操作失败（已降级内存模式）: op={}, 原因: {}", operation, e.getMessage());
        } else {
            redisFailureLogged = true;
            log.warn("需求状态 Redis 首次操作失败，降级为内存存储（重启即失）: op={}, 原因: {}",
                    operation, e.getMessage());
        }
    }
}
