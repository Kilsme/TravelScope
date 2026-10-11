package com.travelscope.agent;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LlmGatewayModel 模型装饰器单元测试（FR-S09 检测标准 2/3 的逻辑层验证）
 * <p>
 * 手写 CountingModel 桩（可控失败/成功），覆盖：fallback 切换（含中途失败）、
 * fallback 也失败上抛、熔断 OPEN 快速失败 → HALF_OPEN 探测恢复、熔断统计目标不漂移
 * （粘滞期间 fallback 抖动不影响熔断器）、探测回切（粘滞 → 探测成功 → 自动回切）。
 * 熔断参数调小加速（窗口 4 / 阈值 50% / 最小 4 / OPEN 300ms）。
 * </p>
 */
class LlmGatewayModelTest {

    /** 计数桩：可控失败（failNext=true 时抛异常），记录调用次数 */
    static class CountingModel implements Model {
        final String name;
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean failNext;
        volatile boolean failMidStream;   // 发 1 个块后中途失败

        CountingModel(String name) {
            this.name = name;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                         GenerateOptions options) {
            calls.incrementAndGet();
            if (failNext) {
                return Flux.error(new RuntimeException("simulated 401 unauthorized"));
            }
            if (failMidStream) {
                ChatResponse chunk = ChatResponse.builder().id("c").build();
                return Flux.just(chunk).concatWith(Flux.error(new RuntimeException("mid-stream failure")));
            }
            return Flux.just(ChatResponse.builder().id("ok-" + name).build());
        }

        @Override
        public String getModelName() {
            return name;
        }
    }

    private static CircuitBreaker fastCb() {
        return CircuitBreaker.of("test-cb", CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .failureRateThreshold(50)
                .minimumNumberOfCalls(4)
                .waitDurationInOpenState(Duration.ofMillis(300))
                .build());
    }

    private static LlmGatewayModel gateway(CountingModel primary, CountingModel fallback,
                                           CircuitBreaker cb) {
        return new LlmGatewayModel(primary, fallback, cb, Duration.ofSeconds(2), true);
    }

    @Test
    @DisplayName("检测标准3：主模型失败 → fallback 到 turbo，仍能拿到响应")
    void testFallbackOnPrimaryFailure() {
        CountingModel primary = new CountingModel("qwen-plus");
        CountingModel fallback = new CountingModel("qwen-turbo");
        primary.failNext = true;

        List<ChatResponse> responses = gateway(primary, fallback, fastCb())
                .stream(List.of(), List.of(), null)
                .collectList().block(Duration.ofSeconds(5));

        assertEquals(1, primary.calls.get());
        assertEquals(1, fallback.calls.get(), "主模型失败后应切 fallback");
        assertTrue(responses != null && !responses.isEmpty(), "用户仍能收到响应");
        assertEquals("ok-qwen-turbo", responses.get(0).getId());
    }

    @Test
    @DisplayName("中途失败（已发块后错误）也切 fallback（框架原生 fallbackModel 不覆盖此场景）")
    void testFallbackOnMidStreamFailure() {
        CountingModel primary = new CountingModel("qwen-plus");
        CountingModel fallback = new CountingModel("qwen-turbo");
        primary.failMidStream = true;

        List<ChatResponse> responses = gateway(primary, fallback, fastCb())
                .stream(List.of(), List.of(), null)
                .collectList().block(Duration.ofSeconds(5));

        assertEquals(1, fallback.calls.get(), "中途失败也应切 fallback");
        assertEquals("ok-qwen-turbo", responses.get(responses.size() - 1).getId());
    }

    @Test
    @DisplayName("fallback 也失败 → 异常上抛（由 ChatService SSE error 兜底）")
    void testFallbackAlsoFails_throwsUp() {
        CountingModel primary = new CountingModel("qwen-plus");
        CountingModel fallback = new CountingModel("qwen-turbo");
        primary.failNext = true;
        fallback.failNext = true;

        assertThrows(Exception.class, () ->
                gateway(primary, fallback, fastCb())
                        .stream(List.of(), List.of(), null)
                        .collectList().block(Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("检测标准2：连续失败达阈值 → 熔断 OPEN（后续快速失败不触达模型）→ 半开探测恢复")
    void testCircuitBreakerOpenAndHalfOpen() throws InterruptedException {
        CountingModel primary = new CountingModel("qwen-plus");
        CountingModel fallback = new CountingModel("qwen-turbo");
        CircuitBreaker cb = fastCb();
        LlmGatewayModel model = gateway(primary, fallback, cb);

        // 4 次主模型失败（minimumNumberOfCalls=4 且失败率 100% > 50%）→ 熔断 OPEN。
        // 注意装饰器的 activeModel 粘滞切换：每次失败后切到 fallback 并保持（防抖），
        // 下轮开始前须重置回 primary（生产回切由探测任务完成，测试用钩子模拟）
        for (int i = 0; i < 4; i++) {
            primary.failNext = true;
            model.resetToPrimary();   // 测试钩子：重置 activeModel（对齐粘滞设计）
            model.stream(List.of(), List.of(), null).collectList().block(Duration.ofSeconds(5));
        }
        assertEquals(4, primary.calls.get());
        assertEquals(4, fallback.calls.get());
        assertEquals(CircuitBreaker.State.OPEN, cb.getState(), "失败率达阈值应 OPEN");

        // OPEN + 粘滞期间：fallback 直供（熔断统计只认 primary，fallback 不套熔断），
        // 不再触达主模型（保护模型网关与延迟）；fallback 仍正常响应（用户无感）。
        // 熔断器的 OPEN→HALF_OPEN 恢复由探测任务驱动（见 testProbeRecoverySwitchesBackToPrimary）
        int primaryBefore = primary.calls.get();
        int fallbackBefore = fallback.calls.get();
        List<ChatResponse> openPeriod = model.stream(List.of(), List.of(), null)
                .collectList().block(Duration.ofSeconds(2));
        assertEquals(primaryBefore, primary.calls.get(), "OPEN 期间不应触达主模型（快速失败）");
        assertEquals(fallbackBefore + 1, fallback.calls.get(), "OPEN 期间应由 fallback 提供服务");
        assertTrue(openPeriod != null && !openPeriod.isEmpty(), "OPEN 期间用户仍能收到 fallback 响应");

        // 等 waitDuration(300ms) 后发起调用——R4j 的 OPEN→HALF_OPEN 是惰性转换：
        // 纯等待不转状态，下一次调用的 permission 检查才触发 HALF_OPEN 并放行探测。
        // 此处 primary 已恢复（failNext=false），但 activeModel 粘滞在 fallback，
        // resetToPrimary（模拟探测回切）后调用走 primary 成功 → 熔断脱离 OPEN。
        Thread.sleep(400);
        primary.failNext = false;
        model.resetToPrimary();
        List<ChatResponse> responses = model.stream(List.of(), List.of(), null)
                .collectList().block(Duration.ofSeconds(5));
        assertTrue(responses != null && !responses.isEmpty(), "半开探测成功应拿到响应");
        // R4j HALF_OPEN 默认放行 10 次探测，全部成功才转 CLOSED——单次探测成功后
        // 状态可能仍为 HALF_OPEN（恢复路径已打开），断言「不再是 OPEN」即可
        assertTrue(cb.getState() == CircuitBreaker.State.CLOSED
                        || cb.getState() == CircuitBreaker.State.HALF_OPEN,
                "探测成功后应脱离 OPEN（实际: " + cb.getState() + "）");
    }

    @Test
    @DisplayName("能力方法委托：getModelName 反映当前生效模型（fallback 切换后变化）")
    void testCapabilityDelegation() {
        CountingModel primary = new CountingModel("qwen-plus");
        CountingModel fallback = new CountingModel("qwen-turbo");
        LlmGatewayModel model = gateway(primary, fallback, fastCb());

        assertEquals("qwen-plus", model.getModelName());
        primary.failNext = true;
        model.stream(List.of(), List.of(), null).collectList().block(Duration.ofSeconds(5));
        assertEquals("qwen-turbo", model.getModelName(), "切换后应反映 fallback");
    }

    @Test
    @DisplayName("统计目标不漂移（2026-10-11 修复）：粘滞期间 fallback 自身抖动不影响熔断器，失败直接上抛")
    void testFallbackJitterDoesNotAffectCircuitBreaker() {
        CountingModel primary = new CountingModel("qwen-plus");
        CountingModel fallback = new CountingModel("qwen-turbo");
        CircuitBreaker cb = fastCb();
        LlmGatewayModel model = gateway(primary, fallback, cb);

        // primary 失败 1 次 → 粘滞 fallback（熔断记 1 次失败，未达最小样本 4，仍 CLOSED）
        primary.failNext = true;
        model.stream(List.of(), List.of(), null).collectList().block(Duration.ofSeconds(5));
        assertEquals("qwen-turbo", model.getModelName(), "应粘滞到 fallback");

        // 粘滞期间 fallback 自身连续失败（次数 ≥ 熔断窗口 4）：每次直接上抛，
        // 熔断统计不受 fallback 健康度影响——修复前熔断器经 activeModel.get() 间接挂载，
        // 粘滞后统计对象漂移到 fallback，fallback 抖动会把熔断器打进 OPEN
        fallback.failNext = true;
        for (int i = 0; i < 4; i++) {
            assertThrows(Exception.class, () ->
                    model.stream(List.of(), List.of(), null).collectList().block(Duration.ofSeconds(5)));
        }
        assertEquals(1, primary.calls.get(), "粘滞期间不应触达主模型");
        assertEquals(5, fallback.calls.get(),
                "fallback 失败直接上抛：1 次成功 + 4 次失败各计 1 次（不再嵌套二次重试）");
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState(),
                "fallback 抖动不应影响熔断器（熔断统计只认 primary）");
    }

    @Test
    @DisplayName("探测回切（替代重启恢复）：OPEN 未到期探测被熔断拦截 → 主模型恢复后探测成功自动回切")
    void testProbeRecoverySwitchesBackToPrimary() throws InterruptedException {
        CountingModel primary = new CountingModel("qwen-plus");
        CountingModel fallback = new CountingModel("qwen-turbo");
        CircuitBreaker cb = fastCb();
        LlmGatewayModel model = gateway(primary, fallback, cb);

        // 4 次主模型失败 → 熔断 OPEN + 粘滞 fallback
        for (int i = 0; i < 4; i++) {
            primary.failNext = true;
            model.resetToPrimary();
            model.stream(List.of(), List.of(), null).collectList().block(Duration.ofSeconds(5));
        }
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
        assertEquals("qwen-turbo", model.getModelName(), "应粘滞到 fallback");

        // OPEN 未到期（waitDuration 300ms 内）：探测被 CallNotPermitted 快速拦截，零网络调用
        int primaryBefore = primary.calls.get();
        assertFalse(model.probePrimaryRecovery().block(Duration.ofSeconds(5)),
                "OPEN 未到期探测应被熔断拦截");
        assertEquals(primaryBefore, primary.calls.get(), "被拦截的探测不应触达主模型");
        assertEquals("qwen-turbo", model.getModelName(), "探测被拦截应保持粘滞");

        // 主模型恢复 + OPEN 到期 → HALF_OPEN 惰性放行探测 → 成功即回切（无需重启）
        Thread.sleep(400);
        primary.failNext = false;
        assertTrue(model.probePrimaryRecovery().block(Duration.ofSeconds(5)),
                "探测成功应回切");
        assertEquals("qwen-plus", model.getModelName(), "回切后应反映主模型");
        assertTrue(cb.getState() == CircuitBreaker.State.CLOSED
                        || cb.getState() == CircuitBreaker.State.HALF_OPEN,
                "探测成功后应脱离 OPEN（实际: " + cb.getState() + "）");

        // 回切后真实流量恢复走 primary
        model.stream(List.of(), List.of(), null).collectList().block(Duration.ofSeconds(5));
        assertEquals(primaryBefore + 2, primary.calls.get(), "探测 1 次 + 回切后调用 1 次");

        // 非粘滞时探测为 no-op（零调用，探测任务常态零开销）
        assertFalse(model.probePrimaryRecovery().block(Duration.ofSeconds(5)));
        assertEquals(primaryBefore + 2, primary.calls.get(), "非粘滞探测不应触达主模型");
    }
}
