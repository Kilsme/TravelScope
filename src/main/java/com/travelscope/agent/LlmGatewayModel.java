package com.travelscope.agent;

import com.travelscope.common.TracingHelper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;

/**
 * LLM Gateway —— 模型调用层装饰器（FR-S09）
 * <p>
 * {@code implements Model} 包装主模型（qwen-plus），对每一次模型调用叠加：
 * <ol>
 *   <li><b>熔断</b>：Resilience4j CircuitBreaker（滑动窗口失败率超阈值 → OPEN 快速失败
 *       → waitDuration 后 HALF_OPEN 探测恢复）——OPEN 期间请求不再打到 DashScope</li>
 *   <li><b>单次调用超时</b>：Flux.timeout（默认 30s）</li>
 *   <li><b>fallback 降级</b>：失败（含中途失败）→ log.warn("fallback → {turbo}") →
 *       切换 qwen-turbo 实例重流；fallback 也失败则异常上抛，由 ChatService 转 SSE error</li>
 * </ol>
 * </p>
 * <p>
 * 框架核验依据（agentscope 2.0.3 反编译）：Model 仅 2 抽象方法 + 3 default，框架自身的
 * fallbackModel 实现就是同款 implements Model 包装器（ReActAgent$2）；ChatModelBase.stream
 * 是 public final（不能继承装饰，必须接口实现）；DashScopeHttpException 不实现
 * ModelHttpException——框架原生重试不认 401/invalid-model，本装饰器是唯一处理点；
 * GenerateOptions.modelName 被 DashScopeChatModel 忽略——fallback 必须持有独立模型实例。
 * 与框架原生 fallbackModel 的差异：原生只在「首个信号是错误」时切换（switchOnFirst），
 * 本装饰器 onErrorResume 覆盖中途失败（丢弃已发块、整流切到 turbo 重发）。
 * </p>
 */
public class LlmGatewayModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(LlmGatewayModel.class);

    private final Model primary;
    private final Model fallback;
    private final CircuitBreaker circuitBreaker;
    private final Duration callTimeout;
    private final boolean fallbackEnabled;
    /** 业务链路打点（model-call span，2026-10-02） */
    private final TracingHelper tracing;

    /** 当前生效模型：fallback 切换后能力标志反映 fallback（对齐框架 activeModel 模式） */
    private final AtomicReference<Model> activeModel;

    public LlmGatewayModel(Model primary, Model fallback, CircuitBreaker circuitBreaker,
                           Duration callTimeout, boolean fallbackEnabled) {
        this(primary, fallback, circuitBreaker, callTimeout, fallbackEnabled, TracingHelper.NOOP);
    }

    public LlmGatewayModel(Model primary, Model fallback, CircuitBreaker circuitBreaker,
                           Duration callTimeout, boolean fallbackEnabled, TracingHelper tracing) {
        this.primary = primary;
        this.fallback = fallback;
        this.circuitBreaker = circuitBreaker;
        this.callTimeout = callTimeout;
        this.fallbackEnabled = fallbackEnabled;
        this.tracing = tracing;
        this.activeModel = new AtomicReference<>(primary);

        // 熔断状态变迁埋点（检测标准 2 的观察点：OPEN / HALF_OPEN / CLOSED 日志）
        circuitBreaker.getEventPublisher()
                .onStateTransition(e -> log.warn("circuit_breaker {} → {} ({}): {}",
                        e.getStateTransition().getFromState(),
                        e.getStateTransition().getToState(),
                        circuitBreaker.getName(), e.getCreationTime()));
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                     GenerateOptions options) {
        // 业务打点（model-call，2026-10-02）：每次模型调用一个 span（ReAct 多轮迭代各一个），
        // 订阅时开启、终态（完成/出错/取消）结束；outcome 三态：success（主模型成功）/
        // fallback（切换降级成功，onErrorResume 内标，tagIfAbsent 防 complete 覆盖）/
        // error（双失败，终态覆盖）
        return Flux.defer(() -> {
            TracingHelper.SpanHandle span = tracing.startSpan("model-call",
                    Map.of("model", String.valueOf(activeModel.get().getModelName())));
            return doStream(messages, tools, options, span)
                    .doOnComplete(() -> {
                        span.tagIfAbsent("outcome", "success");
                        span.end();
                    })
                    .doOnError(e -> {
                        span.tag("outcome", "error");
                        span.error(e);
                        span.end();
                    })
                    .doOnCancel(span::end);
        });
    }

    private Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools,
                                        GenerateOptions options, TracingHelper.SpanHandle span) {
        // 熔断统计挂在「主模型流」上（fallback 之前）：fallback 成功会掩盖主模型故障，
        // 若挂在外层整链，持续 401 时熔断器永远不 OPEN、请求永远打向 DashScope 拿 401。
        // OPEN 期间主模型流直接快速失败（CallNotPermitted），仍可经 fallback 提供服务。
        Flux<ChatResponse> guardedPrimary = Flux.defer(() ->
                        activeModel.get().stream(messages, tools, options))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .timeout(callTimeout);

        if (!fallbackEnabled || fallback == null) {
            return guardedPrimary;
        }
        return guardedPrimary.onErrorResume(e -> {
            // 主模型失败（熔断 OPEN / 超时 / 401 / 中途错误）→ 切 fallback 重流
            log.warn("fallback → {} (primary={} 失败原因: {})",
                    fallback.getModelName(), activeModel.get().getModelName(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            activeModel.set(fallback);
            // 打点内标：本次调用走了 fallback（外层 doOnComplete 的 success 经 tagIfAbsent 不覆盖）
            span.tag("outcome", "fallback");
            return fallback.stream(messages, tools, options);
        });
    }

    @Override
    public String getModelName() {
        return activeModel.get().getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return activeModel.get().supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        // 显式委托（框架内置的 ReActAgent$2 包装器漏了此方法，切模型后能力标志会失真——此处修正）
        return activeModel.get().supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return activeModel.get().getContextWindowSize();
    }

    /**
     * 重置回主模型（测试钩子；生产链路中每个对话的首次调用即走 primary）。
     */
    void resetToPrimary() {
        activeModel.set(primary);
    }
}
