package com.travelscope.service;

import java.time.Duration;

/**
 * 意图级联缓存接口（FR-S01）
 * <p>
 * 承载三层级联中两处 Redis 缓存的读写：
 * <ul>
 *   <li><b>L0 会话延续</b>：会话最近意图（{@code userId:sessionId} 维度），
 *       短追问且缓存 TTL 内有最近意图时直接沿用，0 计算</li>
 *   <li><b>L2 文本缓存</b>：相同消息文本的 L2 分类结果（SHA-256 键），
 *       命中时跳过模型调用</li>
 * </ul>
 * 实现必须对 Redis 故障静默降级（返回 null / no-op），级联自动退化为
 * L1 → L2 → L3，不阻断对话链路。
 * </p>
 */
public interface IntentCache {

    /**
     * 读取该会话的最近意图（L0；TTL 内有效）
     *
     * @return 意图标签（IntentType.name()）；无缓存或 Redis 不可用时返回 null
     */
    String getRecentIntent(String userId, String sessionId);

    /**
     * 写回该会话的最近意图并刷新 TTL（L0；级联每层命中后调用）
     */
    void saveRecentIntent(String userId, String sessionId, String intent, Duration ttl);

    /**
     * 读取 L2 文本缓存（相同消息文本的历史分类结果）
     *
     * @param normalizedMessage 规范化后的消息文本（trim）
     * @return 意图标签；无缓存或 Redis 不可用时返回 null
     */
    String getCachedL2(String normalizedMessage);

    /**
     * 写入 L2 文本缓存
     */
    void saveCachedL2(String normalizedMessage, String intent, Duration ttl);
}
