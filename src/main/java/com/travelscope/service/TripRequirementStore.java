package com.travelscope.service;

import com.travelscope.dto.TripRequirementState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
 * TODO: 迁移 Redis hash {@code trip:req:{userId}:{sessionId}}（FR-S02，
 * 与 AgentState 同隔离级别）；当前骨架为内存版，应用重启即失。
 * </p>
 */
@Service
public class TripRequirementStore {

    private static final Logger log = LoggerFactory.getLogger(TripRequirementStore.class);

    /** 反问轮次上限（FR-S02：3 轮仍缺则 DEGRADED 放行） */
    public static final int MAX_CLARIFY_CYCLES = 3;

    /** 注册键 userId:sessionId → 需求状态机 */
    private final Map<String, TripRequirementState> states = new ConcurrentHashMap<>();

    /**
     * 取该会话的状态机（无则新建，首次进入 COLLECTING）
     */
    public TripRequirementState get(String userId, String sessionId) {
        return states.computeIfAbsent(containerKey(userId, sessionId),
                k -> {
                    log.info("需求状态机初始化: 用户={}, 会话={}", userId, sessionId);
                    return new TripRequirementState();
                });
    }

    /**
     * 判断必填项是否已收齐（DONE 或 DEGRADED 均视为可放行进入规划）
     */
    public boolean isCollected(String userId, String sessionId) {
        TripRequirementState s = states.get(containerKey(userId, sessionId));
        return s != null && s.missingFields().isEmpty();
    }

    /**
     * 反问轮次 +1（intake-agent 发起一轮反问时由 RequirementTools 调用）
     */
    public void incrementClarifyCycles(String userId, String sessionId) {
        TripRequirementState s = get(userId, sessionId);
        s.clarifyCycles++;
        if (s.clarifyCycles >= MAX_CLARIFY_CYCLES && !s.missingFields().isEmpty()) {
            s.status = TripRequirementState.Status.DEGRADED;
            log.info("需求收集 DEGRADED 放行: 用户={}, 会话={}, 已反问 {} 轮仍缺 {}",
                    userId, sessionId, s.clarifyCycles, s.missingFields());
        }
    }

    /**
     * 清除该会话的状态（需求收齐、规划完成后可清理；预留，暂无调用方）
     */
    public void clear(String userId, String sessionId) {
        states.remove(containerKey(userId, sessionId));
    }

    private static String containerKey(String userId, String sessionId) {
        return userId + ":" + sessionId;
    }
}
