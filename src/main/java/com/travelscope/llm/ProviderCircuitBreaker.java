package com.travelscope.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * v5-F3 provider 级熔断器（自定义状态机，全 CAS 无锁，2026-10-22）。
 * <p>
 * 状态迁移全部经 {@code AtomicReference.compareAndSet}——CAS 失败即并发方已迁移、本次放弃
 * （天然幂等）；探针单飞行由 {@link #tryEnterHalfOpenIfCooldownExpired} 的 CAS(OPEN→HALF_OPEN)
 * 保证：并发扫描/重复触发只有一个赢者获得探测权，探测在途期间状态为 HALF_OPEN，
 * 业务选路（{@link #routeAllowed()} 仅放行 CLOSED）与后续扫描都自然跳过。
 * </p>
 * <p>
 * 触发口径（用户拍板）：①单次响应超时 → <b>立即 OPEN</b>（挂死模型马上出局，不等统计达标）；
 * ②统计口径——累计总调用 &gt; {@code minCalls} 且成功率 &lt; {@code minSuccessRate} 才 OPEN
 * （普通失败不足条件不熔断，防偶发失败误伤；探针恢复成功后计数清零重新累计）。
 * </p>
 * <p>
 * 恢复：OPEN 冷却到期 → 探针调度器 CAS 转 HALF_OPEN 并放一个最小探测请求（业务流量不路由
 * 半开 provider）；探测通过 → CLOSED 回选路 + 计数清零；不通 → 继续 OPEN 重新计时。
 * 全部状态迁移打日志锚点 {@code model_breaker {pool/provider} {from}→{to}}。
 * </p>
 * <p>
 * 计数竞态取舍（不引入锁）：total/success 用 AtomicLong 自增，统计判定在自增后读快照——
 * 微竞态最差多/少计一次调用，不影响熔断正确性。openedAtMillis 写在 CAS 之前、并发 trip 时
 * 后写者覆盖，冷却起点偏移几毫秒无害。
 * </p>
 */
public class ProviderCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(ProviderCircuitBreaker.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String id;
    private final long minCalls;
    private final double minSuccessRate;
    private final long openWaitMillis;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicLong total = new AtomicLong();
    private final AtomicLong success = new AtomicLong();
    private volatile long openedAtMillis;

    public ProviderCircuitBreaker(String id, long minCalls, double minSuccessRate, long openWaitMillis) {
        this.id = id;
        this.minCalls = minCalls;
        this.minSuccessRate = minSuccessRate;
        this.openWaitMillis = openWaitMillis;
    }

    /** 业务选路：仅 CLOSED 放行（HALF_OPEN 只留给探针，OPEN 摘除） */
    public boolean routeAllowed() {
        return state.get() == State.CLOSED;
    }

    /**
     * 探针调度入口：OPEN 且冷却到期才尝试转半开。CAS 赢者获得唯一探测权——
     * 并发调用只有一个返回 true（探针单飞行的原子保证）。
     */
    public boolean tryEnterHalfOpenIfCooldownExpired(long nowMillis) {
        if (state.get() != State.OPEN) {
            return false;
        }
        if (nowMillis - openedAtMillis < openWaitMillis) {
            return false;
        }
        if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
            log.warn("model_breaker {} OPEN→HALF_OPEN (冷却 {}ms 到期，派发探测)", id, openWaitMillis);
            return true;
        }
        return false;
    }

    /** 调用成功：半开探测成功 → CLOSED + 计数清零；否则累计成功 */
    public void onSuccess() {
        if (state.get() == State.HALF_OPEN && state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
            total.set(0);
            success.set(0);
            log.warn("model_breaker {} HALF_OPEN→CLOSED (探测通过，恢复选路，计数清零)", id);
            return;
        }
        total.incrementAndGet();
        success.incrementAndGet();
    }

    /** 调用失败：半开探测失败 → 重新 OPEN；普通失败走统计判定（不足条件不熔断） */
    public void onFailure() {
        if (state.get() == State.HALF_OPEN) {
            reopenFromHalfOpen("探测失败");
            return;
        }
        total.incrementAndGet();
        evaluateStats();
    }

    /** 响应超时：立即 OPEN（不等统计达标——挂死模型马上出局） */
    public void onTimeout() {
        if (state.get() == State.HALF_OPEN) {
            reopenFromHalfOpen("探测超时");
            return;
        }
        total.incrementAndGet();
        trip("响应超时");
    }

    private void evaluateStats() {
        long t = total.get();
        long s = success.get();
        if (t > minCalls && (double) s / t < minSuccessRate) {
            trip(String.format("统计达标: total=%d successRate=%.3f (门槛 %d/%.2f)",
                    t, (double) s / t, minCalls, minSuccessRate));
        }
    }

    private void trip(String reason) {
        openedAtMillis = System.currentTimeMillis();
        if (state.compareAndSet(State.CLOSED, State.OPEN)) {
            log.warn("model_breaker {} CLOSED→OPEN ({})", id, reason);
        }
    }

    private void reopenFromHalfOpen(String reason) {
        openedAtMillis = System.currentTimeMillis();
        if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
            log.warn("model_breaker {} HALF_OPEN→OPEN ({}, 重新计时)", id, reason);
        }
    }

    // ==================== 观测/测试 ====================

    public State get() {
        return state.get();
    }

    public long total() {
        return total.get();
    }

    public long successCount() {
        return success.get();
    }
}
