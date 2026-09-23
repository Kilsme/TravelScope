package com.travelscope.service;

import com.travelscope.config.AppProperties;
import com.travelscope.config.AppProperties.LlmGatewayConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * LLM Gateway —— 对话准入层（FR-S09）
 * <p>
 * ChatService 每轮对话开始前 {@link #tryAcquire}，结束（done/error）时 {@link #release}：
 * <ul>
 *   <li><b>全局并发</b>：Semaphore(50)，等待 acquireTimeoutMs，拿不到 → 明确提示（不白屏）</li>
 *   <li><b>单用户并发</b>：Semaphore(2)/用户（当前无认证体系，guest 即全站上限），
 *       拿不到 → 「您的请求处理中，请等上一条完成后再发」</li>
 *   <li><b>全局 QPS</b>：Resilience4j RateLimiter 滑动窗口（默认 10/s），
 *       超频 → 「请求过于频繁，请稍后再试」</li>
 * </ul>
 * 模型调用层的熔断/单次超时/fallback 见 {@code LlmGatewayModel}（职责分层：对话粒度 here，
 * 模型调用粒度 there）。全部拒绝路径返回用户可读文案，由 ChatService 转 SSE error 事件。
 * </p>
 */
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    /** 准入结果：allowed=false 时 message 为用户可读的降级文案 */
    public record AcquireResult(boolean allowed, String reason, String message) {
        static AcquireResult ok() {
            return new AcquireResult(true, null, null);
        }
    }

    private final LlmGatewayConfig config;
    private final Semaphore globalSemaphore;
    private final RateLimiter rateLimiter;
    /** userId → 单用户信号量（懒创建；并发安全） */
    private final Map<String, Semaphore> perUserSemaphores = new ConcurrentHashMap<>();

    public LlmGateway(AppProperties appProperties) {
        this.config = appProperties.getLlmGateway();
        this.globalSemaphore = new Semaphore(config.getGlobalConcurrency());
        this.rateLimiter = RateLimiter.of("llm-gateway-admission",
                RateLimiterConfig.custom()
                        .limitForPeriod((int) Math.max(1, config.getGlobalQps()))
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        .timeoutDuration(Duration.ZERO)   // 不等待：超频即拒绝
                        .build());
        log.info("LLM Gateway 初始化: 全局并发={}, 单用户并发={}, QPS={}, 准入等待={}ms, 快/慢泳道={}/{}s",
                config.getGlobalConcurrency(), config.getPerUserConcurrency(), config.getGlobalQps(),
                config.getAcquireTimeoutMs(), config.getFastLaneTimeoutSeconds(),
                config.getSlowLaneTimeoutSeconds());
    }

    /**
     * 对话准入：全局并发 → QPS → 单用户并发（任一拒绝即返回明确文案）
     * <p>失败路径只回滚已拿到的信号量（按获取逆序），成功路径由 release 统一释放。</p>
     */
    public AcquireResult tryAcquire(String userId) {
        if (!config.isEnabled()) {
            return new AcquireResult(true, null, null);
        }
        long start = System.nanoTime();

        // 1. 全局并发（等待 acquireTimeoutMs）
        if (!tryAcquire(globalSemaphore, config.getAcquireTimeoutMs())) {
            return reject("GLOBAL_BUSY",
                    "当前使用人数较多，请您稍等约 1 分钟后重试，感谢耐心等待 🙏", start);
        }

        // 2. 全局 QPS（滑动窗口，不等待）；超频回滚全局信号量
        if (!rateLimiter.acquirePermission()) {
            globalSemaphore.release();
            return reject("QPS_LIMIT", "请求过于频繁，请您稍等几秒后再发～", start);
        }

        // 3. 单用户并发（等待 acquireTimeoutMs 的一半，快速失败）；被拒回滚全局信号量
        Semaphore userSemaphore = perUserSemaphores.computeIfAbsent(userId,
                k -> new Semaphore(config.getPerUserConcurrency()));
        if (!tryAcquire(userSemaphore, Math.max(200, config.getAcquireTimeoutMs() / 2))) {
            globalSemaphore.release();
            return reject("USER_BUSY",
                    "您的上一条请求还在处理中，请等它完成后再发新消息（复杂规划可能需要 1 分钟左右）",
                    start);
        }

        log.info("gateway_admit userId={} latency={}ms", userId, elapsedMs(start));
        return new AcquireResult(true, null, null);
    }

    /**
     * 释放该用户的对话槽位（对话结束 done/error 时必须调用，否则槽位泄漏）
     */
    public void release(String userId) {
        if (!config.isEnabled()) {
            return;
        }
        globalSemaphore.release();
        Semaphore userSemaphore = perUserSemaphores.get(userId);
        if (userSemaphore != null) {
            userSemaphore.release();
        }
    }

    /**
     * 对话级泳道超时（秒）：PLANNING → 慢泳道；其余（CHAT/TOOL_CALL/RAG/null）→ 快泳道
     */
    public Duration laneTimeout(boolean planning) {
        return planning
                ? Duration.ofSeconds(config.getSlowLaneTimeoutSeconds())
                : Duration.ofSeconds(config.getFastLaneTimeoutSeconds());
    }

    /**
     * 全局剩余许可（LoadShedService 采样用）：剩余量与总量的比值即「并发水位」。
     * <p>信号量 permits 只增不减地反映当前并发对话数（tryAcquire/release 对称维护），
     * 无需额外计数器。</p>
     */
    public int availableGlobalPermits() {
        return config.isEnabled() ? globalSemaphore.availablePermits() : Integer.MAX_VALUE;
    }

    /** 全局并发总量（LoadShedService 计算剩余占比用） */
    public int totalGlobalPermits() {
        return config.getGlobalConcurrency();
    }

    /**
     * 清理空闲用户的单用户信号量（@Scheduled 每 10 分钟调用，2026-09-23 并发改造）：
     * 许可全部归还（=该用户当前无进行中对话）的条目直接移除——2000 用户下 Map
     * 无限缓涨（每条约几十字节，量小但属慢性泄漏）。
     * <p>竞态说明：remove 与并发 tryAcquire 的 computeIfAbsent 竞争最坏情况是各自建一个
     * 信号量对象（旧对象仍被在途对话 release，无 permits 泄漏到全局；新对话用新对象从
     * 满额开始）——可接受的弱一致。</p>
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    public void cleanupIdleUserSemaphores() {
        if (!config.isEnabled()) {
            return;
        }
        int before = perUserSemaphores.size();
        perUserSemaphores.entrySet().removeIf(e ->
                e.getValue().availablePermits() >= config.getPerUserConcurrency());
        int removed = before - perUserSemaphores.size();
        if (removed > 0) {
            log.debug("单用户信号量清理: 移除 {} 个空闲条目（剩余 {}）", removed, perUserSemaphores.size());
        }
    }

    private AcquireResult reject(String reason, String message, long start) {
        log.warn("gateway_reject reason={} latency={}ms 文案={}", reason, elapsedMs(start), message);
        return new AcquireResult(false, reason, message);
    }

    private static boolean tryAcquire(Semaphore semaphore, long timeoutMs) {
        try {
            return semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
