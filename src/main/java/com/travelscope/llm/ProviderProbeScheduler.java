package com.travelscope.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v5-F3 探针调度器（2026-10-22）：周期扫描全部注册的池（chat main/reviewer + embedding），
 * 对 OPEN 且冷却到期的 provider <b>CAS 抢单</b>转 HALF_OPEN 并派发一个最小探测请求——
 * 并发扫描/重复触发只有一个赢者（探针单飞行的原子保证），探测在途期间（HALF_OPEN）后续
 * 扫描与业务选路都自然跳过。探测结果经 {@link ProviderCircuitBreaker#onSuccess}/
 * {@link ProviderCircuitBreaker#onFailure} 回填：成功 → CLOSED 恢复选路+计数清零；
 * 失败 → 重新 OPEN 重新计时。
 * <p>
 * 探测在虚拟线程执行（IO 密集按项目线程池约定），不阻塞调度线程；探测打的是真实模型
 * （"ping" 最小请求），不经业务选路、不计入业务统计。
 * </p>
 */
@Component
public class ProviderProbeScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProviderProbeScheduler.class);

    /** 一个可探测目标：熔断器 + 最小探测动作（由池注册时闭包提供） */
    public interface ProbeTarget {
        ProviderCircuitBreaker breaker();

        /** 发一个最小探测请求：成功返回 / 失败抛异常（含超时） */
        void probe() throws Exception;
    }

    private final List<ProbeTarget> targets = new CopyOnWriteArrayList<>();
    private final ExecutorService probeExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public void register(ProbeTarget target) {
        targets.add(target);
    }

    @Scheduled(fixedDelayString = "${travelscope.model-pool.breaker.probe-interval-seconds:10}000",
            initialDelayString = "${travelscope.model-pool.breaker.probe-interval-seconds:10}000")
    public void probeExpiredOpenProviders() {
        long now = System.currentTimeMillis();
        for (ProbeTarget target : targets) {
            // CAS 抢单：冷却到期且本调度周期唯一获得探测权的才派发
            if (target.breaker().tryEnterHalfOpenIfCooldownExpired(now)) {
                probeExecutor.submit(() -> {
                    try {
                        target.probe();
                        target.breaker().onSuccess();
                    } catch (Exception e) {
                        log.info("model_breaker 探测未通过（{}: {}），继续保持熔断",
                                e.getClass().getSimpleName(), e.getMessage());
                        target.breaker().onFailure();
                    }
                });
            }
        }
    }
}
