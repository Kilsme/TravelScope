package com.travelscope.service;

import com.travelscope.config.AppProperties;
import com.travelscope.config.AppProperties.LoadShedConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 过载降级服务（2026-09-23 并发改造：弹性降级优先策略的核心）
 * <p>
 * 2000 人在线目标下不赌单机扛满——任何服务器规格都「稳定不倒」：
 * 采样 JVM 堆使用率 + LlmGateway 全局剩余并发，三级状态（比 LlmGateway.tryAcquire 更外层的闸门）：
 * <ul>
 *   <li><b>GREEN</b>：正常放行</li>
 *   <li><b>YELLOW</b>（堆 &gt;75% 或网关剩余并发 &lt;10%）：拒绝新的 PLANNING 请求
 *       （慢泳道是内存大头，砍掉增量先保命），快请求（CHAT/TOOL_CALL/RAG）正常服务</li>
 *   <li><b>RED</b>（堆 &gt;90% 或网关并发耗尽）：所有新对话拒绝，进行中的对话不中断
 *       （不 dispose 已订阅流，只挡增量）</li>
 * </ul>
 * 指标回落即自动恢复（GREEN/YELLOW 之间滞回：RED 恢复到 YELLOW 需堆 &lt;85%，
 * 防止在阈值附近抖动）。状态变化打 {@code load_shed level=... reason=...} 日志。
 * </p>
 * <p>
 * 采样方式：@Scheduled 每 2s 刷新（非请求时实时读——MemoryMXBean 读堆是廉价操作，
 * 但保持在单线程里做状态迁移判断，避免并发请求各自判定产生撕裂）；请求侧只读
 * AtomicReference 的最新状态，零锁。
 * </p>
 */
@Service
public class LoadShedService {

    private static final Logger log = LoggerFactory.getLogger(LoadShedService.class);

    public enum Level {
        GREEN, YELLOW, RED
    }

    private final LoadShedConfig config;
    private final LlmGateway llmGateway;
    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

    /** 当前降级级别（请求侧零锁读取） */
    private final AtomicReference<Level> level = new AtomicReference<>(Level.GREEN);

    public LoadShedService(AppProperties appProperties, LlmGateway llmGateway) {
        this.config = appProperties.getLoadShed();
        this.llmGateway = llmGateway;
        log.info("过载降级初始化: enabled={}, 堆阈值 YELLOW={}/RED={}, 并发剩余 YELLOW<{}",
                config.isEnabled(), config.getHeapYellowThreshold(), config.getHeapRedThreshold(),
                config.getPermitsYellowRatio());
    }

    /**
     * 准入判断（ChatService 在 LlmGateway.tryAcquire 之前调用）：
     * 返回 null = 放行；非 null = 拒绝文案（直接 SSE error，不占任何槽位）
     */
    public String tryAdmit(boolean planning) {
        if (!config.isEnabled()) {
            return null;
        }
        Level current = level.get();
        if (current == Level.GREEN) {
            return null;
        }
        if (current == Level.YELLOW) {
            return planning
                    ? "规划功能当前排队较多，建议稍后再试；您可以先进行天气、车票等快速查询～"
                    : null;   // YELLOW 只挡 PLANNING
        }
        // RED：所有新对话拒绝
        return "系统当前较为繁忙，请稍候片刻再试，感谢您的耐心等待 🙏";
    }

    /** 当前级别（管理端状态接口用） */
    public Level getLevel() {
        return level.get();
    }

    /** 最近一次降级原因（观测用） */
    private volatile String lastReason = "正常";

    public String getLastReason() {
        return lastReason;
    }

    /**
     * 采样与状态迁移（每 2s）：单线程串行判定，状态迁移带滞回。
     */
    @Scheduled(fixedDelay = 2000)
    public void sample() {
        if (!config.isEnabled()) {
            return;
        }
        double heapUsed = heapUsage();
        double permitsRatio = (double) llmGateway.availableGlobalPermits()
                / Math.max(1, llmGateway.totalGlobalPermits());
        boolean permitsExhausted = llmGateway.availableGlobalPermits() <= 0;

        Level next = evaluate(heapUsed, permitsRatio, permitsExhausted);
        Level prev = level.getAndSet(next);
        if (prev != next) {
            log.warn("load_shed {} → {} reason={}", prev, next, lastReason);
        }
    }

    /**
     * 纯函数判定（可单测）：堆与并发水位 → 目标级别。
     * <p>滞回：RED → YELLOW 需堆降到 85% 以下（而非 90%），避免阈值附近抖动。</p>
     */
    Level evaluate(double heapUsed, double permitsRatio, boolean permitsExhausted) {
        Level current = level.get();
        // RED：堆超红线 或 并发耗尽（RED 保持需堆 < 85% 才降回 YELLOW）
        boolean redCondition = heapUsed >= config.getHeapRedThreshold() || permitsExhausted;
        boolean redRecover = current == Level.RED && heapUsed >= config.getHeapRedThreshold() - 0.05;
        if (redCondition || redRecover) {
            lastReason = String.format("heap:%.0f%% permits:%.0f%%", heapUsed * 100, permitsRatio * 100);
            return Level.RED;
        }
        // YELLOW：堆超黄线 或 剩余并发占比低于阈值
        if (heapUsed >= config.getHeapYellowThreshold() || permitsRatio < config.getPermitsYellowRatio()) {
            lastReason = String.format("heap:%.0f%% permits_remaining:%.0f%%", heapUsed * 100, permitsRatio * 100);
            return Level.YELLOW;
        }
        lastReason = String.format("heap:%.0f%% permits_remaining:%.0f%%", heapUsed * 100, permitsRatio * 100);
        return Level.GREEN;
    }

    /** JVM 堆使用率（0~1）：heap used / heap max */
    private double heapUsage() {
        try {
            long used = memoryMXBean.getHeapMemoryUsage().getUsed();
            long max = memoryMXBean.getHeapMemoryUsage().getMax();
            return max > 0 ? (double) used / max : 0.0;
        } catch (Exception e) {
            return 0.0;   // 采样失败按健康处理（降级闭门比误伤好）
        }
    }
}
