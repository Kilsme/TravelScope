package com.travelscope.service;

import io.agentscope.core.model.ChatUsage;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单轮对话的模型调用 token 用量累计器（P1-K 真实采集，2026-10-11）
 * <p>
 * 采集点：ChatService 消费的 agent 事件流中每个 {@code ModelCallEndEvent} 携带一次模型调用的
 * 真实 {@link ChatUsage}（ReAct 多轮迭代每轮一次；子代理调用随事件流上浮时一并计入）。
 * per-turn 实例（非共享），天然无并发归属问题。
 * </p>
 * <p>
 * 降级语义：网关不回 usage（如 GLMFormatter 剥离 stream_options 后）时本器保持
 * {@code hasRealUsage()=false}，调用方回落旧的字符估算口径——报表口径不变差。
 * </p>
 */
class TurnUsageTracker {

    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicBoolean sawUsage = new AtomicBoolean();

    /** 累计一次模型调用的真实 usage（null 或全 0 视为「网关未回」忽略） */
    void onModelCallEnd(ChatUsage usage) {
        if (usage == null || (usage.getInputTokens() <= 0 && usage.getOutputTokens() <= 0)) {
            return;
        }
        sawUsage.set(true);
        inputTokens.addAndGet(usage.getInputTokens());
        outputTokens.addAndGet(usage.getOutputTokens());
    }

    /** 本轮是否拿到过真实 usage（false → 调用方回落估算口径） */
    boolean hasRealUsage() {
        return sawUsage.get();
    }

    /** 本轮全部可见模型调用的 input+output 总和 */
    long totalTokens() {
        return inputTokens.get() + outputTokens.get();
    }
}
