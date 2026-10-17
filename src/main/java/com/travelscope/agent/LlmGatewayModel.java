package com.travelscope.agent;

import com.travelscope.common.TracingHelper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;

/**
 * LLM Gateway —— 模型调用层装饰器（FR-S09）
 * <p>
 * {@code implements Model} 包装主链路模型（glm-5-3-260814 @ 模型网关），对每一次模型调用叠加：
 * <ol>
 *   <li><b>熔断</b>：Resilience4j CircuitBreaker（滑动窗口失败率超阈值 → OPEN 快速失败
 *       → waitDuration 后 HALF_OPEN 探测恢复）——OPEN 期间请求不再打到模型网关</li>
 *   <li><b>超时分段</b>（2026-10-11 P2-G）：首包探测超时（首个 token 默认 10s 未到达 →
 *       挂死快速失败切 fallback，区分「模型坏了」与「慢慢生成长行程」）+ 首包后每块
 *       间隙 callTimeout（默认 30s）保护</li>
 *   <li><b>fallback 降级</b>（首包提交闸门，2026-10-11 P0-B）：<b>首包前失败</b>（连接失败 /
 *       超时 / 401 / 模型不存在 / 熔断 OPEN——最常见失败形态）→ log.warn("fallback → …") →
 *       切换 deepseek-v4-flash-0731 干净重发并粘滞（防抖），用户只看到 fallback 的响应；
 *       <b>首包后中途失败</b>（首个非空块已下发）→ 已发块不可回收（Flux 语义），不再切换
 *       （避免「primary 前半段 + fallback 全量重发」拼接输出），异常上抛由 ChatService
 *       转 SSE error。注意主/备同网关（2026-10-05 迁移后）：网关整体故障时 fallback 同失效，
 *       由熔断 OPEN 快速失败 + SSE error 文案兜底</li>
 *   <li><b>探测回切</b>：粘滞期间由 @Scheduled 定时任务对 primary 发最小探测调用（走熔断器，
 *       OPEN 未到期被 CallNotPermitted 快速拦截、零网络开销；到期经 HALF_OPEN 惰性转换放行），
 *       探测成功即自动回切 primary——替代早期的「重启恢复」（2026-10-11 熔断统计漂移修复）</li>
 * </ol>
 * </p>
 * <p>
 * 框架核验依据（agentscope 2.0.3 反编译）：Model 仅 2 抽象方法 + 3 default，框架自身的
 * fallbackModel 实现就是同款 implements Model 包装器（ReActAgent$2）；ChatModelBase.stream
 * 是 public final（不能继承装饰，必须接口实现）；模型侧 HTTP 异常（401/invalid-model 等）
 * 不实现 ModelHttpException——框架原生重试不认它们，本装饰器是唯一处理点；
 * OpenAIChatModel 虽尊重 per-call GenerateOptions.modelName，但运行时无法在单实例上
 * 切换模型（per-call options 由框架传入），fallback 仍需持有独立的轻量模型实例。
 * 与框架原生 fallbackModel 的差异：原生只在「首个信号是错误」时切换（switchOnFirst）；
 * 本装饰器经首包提交闸门把切换窗口扩展到「首包前失败」（连接失败/401/模型不存在——
 * 最常见形态，用户无感）；首包后中途失败已发块不可回收，如实上抛转 SSE error，
 * 不再拼接重发。
 * </p>
 */
public class LlmGatewayModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(LlmGatewayModel.class);

    private com.travelscope.assessment.ObservationStore assessment;
    public LlmGatewayModel assessment(com.travelscope.assessment.ObservationStore value){this.assessment=value;return this;}
    private final Model primary;
    private final Model fallback;
    private final CircuitBreaker circuitBreaker;
    private final Duration callTimeout;
    /** 首包探测超时（P2-G）：首个 token 到达判据，挂死快速失败；应明显小于 callTimeout */
    private final Duration firstPacketTimeout;
    private final boolean fallbackEnabled;
    /** 业务链路打点（model-call span，2026-10-02） */
    private final TracingHelper tracing;

    /** 当前生效模型：fallback 切换后能力标志反映 fallback（对齐框架 activeModel 模式）；
     *  粘滞防抖——切到 fallback 后保持，由探测任务 {@link #probePrimaryRecovery()} 回切 */
    private final AtomicReference<Model> activeModel;

    /** 探测回切并发防护：同一时间至多一个在途探测 */
    private final AtomicBoolean probing = new AtomicBoolean();

    public LlmGatewayModel(Model primary, Model fallback, CircuitBreaker circuitBreaker,
                           Duration callTimeout, boolean fallbackEnabled) {
        this(primary, fallback, circuitBreaker, callTimeout, Duration.ofSeconds(10),
                fallbackEnabled, TracingHelper.NOOP);
    }

    public LlmGatewayModel(Model primary, Model fallback, CircuitBreaker circuitBreaker,
                           Duration callTimeout, boolean fallbackEnabled, TracingHelper tracing) {
        this(primary, fallback, circuitBreaker, callTimeout, Duration.ofSeconds(10),
                fallbackEnabled, tracing);
    }

    public LlmGatewayModel(Model primary, Model fallback, CircuitBreaker circuitBreaker,
                           Duration callTimeout, Duration firstPacketTimeout, boolean fallbackEnabled) {
        this(primary, fallback, circuitBreaker, callTimeout, firstPacketTimeout, fallbackEnabled,
                TracingHelper.NOOP);
    }

    public LlmGatewayModel(Model primary, Model fallback, CircuitBreaker circuitBreaker,
                           Duration callTimeout, Duration firstPacketTimeout,
                           boolean fallbackEnabled, TracingHelper tracing) {
        this.primary = primary;
        this.fallback = fallback;
        this.circuitBreaker = circuitBreaker;
        this.callTimeout = callTimeout;
        this.firstPacketTimeout = firstPacketTimeout;
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
        Model current = activeModel.get();   // 粘滞语义不变：当前生效模型
        // 熔断统计只挂「主模型流」（fallback 之前）：fallback 成功会掩盖主模型故障，
        // 若挂在外层整链，持续 401 时熔断器永远不 OPEN、请求永远打向模型网关拿 401。
        // 熔断器是主模型的保护开关——fallback 直调不套熔断（2026-10-11 统计目标漂移修复：
        // 修复前熔断器经 activeModel.get() 间接挂载，粘滞后统计/探测对象漂移到 fallback）。
        // OPEN 期间主模型流直接快速失败（CallNotPermitted），仍可经 fallback 提供服务。
        // 超时分段（2026-10-11 P2-G 首包探测）：整流单档 30s 无法区分「模型坏了」（挂死无
        // 响应）与「慢慢生成长行程」——首块限时 firstPacketTimeout（默认 10s）内未到达即判失败
        // （挂死快速失败，经 P0-B 闸门未提交 → 干净切 fallback）；首块到达后探测通过，后续
        // 每块间隙恢复 callTimeout 保护（慢生成不误杀）。
        // 注意重载坑：timeout(Duration, lambda) 会把 lambda 误绑为 fallback Publisher
        // （timeout 挂起不换流）——必须用 timeout(Publisher 首档, Function 后续档)。
        Flux<ChatResponse> call = Flux.defer(() -> current.stream(messages, tools, options))
                .timeout(Mono.delay(firstPacketTimeout), chunk -> Mono.delay(callTimeout));
        Flux<ChatResponse> guarded = (current == primary)
                ? call.transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                : call;

        if (!fallbackEnabled || fallback == null || current == fallback) {
            // 已在 fallback 档：不再嵌套 fallback，失败直接上抛（由 ChatService 转 SSE error）
            return guarded;
        }
        // 首包提交闸门（2026-10-11，P0-B；即 P2-G 首包探测的共用缓冲层）：首个非空块到达前，
        // 用户尚未看到任何内容，失败可干净切 fallback 重发；首个非空块下发后（committed），
        // 已发块经 ChatService 转成 SSE 事件推给前端、Flux 语义上不可回收——中途失败不再切
        // fallback（根治「primary 前半段 + fallback 全量重发」的拼接输出），异常上抛转 SSE error。
        // 注意 committed 为 per-subscription 局部状态（本方法在 stream() 的 Flux.defer 内被调用）。
        AtomicBoolean committed = new AtomicBoolean();
        return guarded
                .doOnNext(chunk -> {
                    if (chunk.getContent() != null && !chunk.getContent().isEmpty()) {
                        committed.set(true);   // 首个非空块 = 首包（role-only 等空块不提交）
                    }
                })
                .onErrorResume(e -> {
                    if (committed.get()) {
                        // 首包后中途失败：不再切换，已发前缀保留，如实失败
                        log.warn("首包已提交，中途失败不切 fallback（primary={} 失败原因: {}）",
                                current.getModelName(),
                                e.getClass().getSimpleName() + ": " + e.getMessage());
                        return Flux.error(e);
                    }
                    // 首包前失败（连接失败 / 超时 / 401 / 模型不存在 / 熔断 OPEN CallNotPermitted）
                    // → fallback 干净重发并粘滞
                    log.warn("fallback → {} (primary={} 失败原因: {})",
                            fallback.getModelName(), current.getModelName(),
                            e.getClass().getSimpleName() + ": " + e.getMessage());
                    activeModel.set(fallback);
                    // 打点内标：本次调用走了 fallback（外层 doOnComplete 的 success 经 tagIfAbsent 不覆盖）
                    span.tag("outcome", "fallback");
                    return Flux.deferContextual(context -> {
                        com.travelscope.assessment.ObservationContext association=context.getOrDefault(com.travelscope.assessment.ObservationContext.KEY,null);
                        if(assessment!=null&&association!=null&&assessment.enabled()) {
                            var transition=assessment.span(association,"FALLBACK",current.getModelName()+" -> "+fallback.getModelName(),null,null);
                            transition.end("RECOVERING",null,e);
                        }
                        return fallback.stream(messages,tools,options);
                    });
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
     * 探测回切调度壳（替代「重启恢复」，2026-10-11）：粘滞 fallback 期间每
     * travelscope.llm-gateway.probe-interval-seconds（默认 30s）触发一次。
     * 核心逻辑在 {@link #probePrimaryRecovery()}（返回 Mono 供测试直调；@Scheduled 不订阅
     * 响应式返回，故在此 subscribe 驱动）。
     */
    @Scheduled(fixedDelayString = "${travelscope.llm-gateway.probe-interval-seconds:30}000",
            initialDelayString = "${travelscope.llm-gateway.probe-interval-seconds:30}000")
    public void schedulePrimaryProbe() {
        probePrimaryRecovery().subscribe(recovered -> {
            if (recovered) {
                log.warn("primary 探测成功 → 自动回切（熔断状态 {}）", circuitBreaker.getState());
            }
        });
    }

    /**
     * 探测回切核心：仅粘滞期间（当前生效模型为 fallback）对 primary 发一次最小探测调用，
     * <b>走熔断器</b>——OPEN 未到期被 CallNotPermitted 快速拦截（零网络开销）；OPEN 到期经
     * R4j 惰性转换 HALF_OPEN 放行探测。探测成功即证明主模型恢复 → 自动回切 primary
     * （回切后真实流量自然把 HALF_OPEN 推到 CLOSED；若仍抖动则失败重新 OPEN → 再粘滞，自校正）。
     * 探测失败计入熔断统计（探测的正是主模型健康度），保持粘滞。
     *
     * @return true=本次探测成功且已回切；false=未粘滞（no-op，零调用）/探测被熔断拦截/探测失败
     */
    public Mono<Boolean> probePrimaryRecovery() {
        if (fallback == null || activeModel.get() != fallback) {
            return Mono.just(false);   // 未粘滞：无需探测
        }
        if (!probing.compareAndSet(false, true)) {
            return Mono.just(false);   // 已有在途探测
        }
        return Flux.defer(() -> primary.stream(
                        List.of(Msg.builder().role(MsgRole.USER).textContent("ping").build()),
                        List.of(), null))
                .timeout(callTimeout)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .collectList()
                .map(chunks -> !chunks.isEmpty() && activeModel.compareAndSet(fallback, primary))
                .onErrorResume(e -> {
                    log.debug("primary 探测未恢复（{}: {}）",
                            e.getClass().getSimpleName(), e.getMessage());
                    return Mono.just(false);
                })
                .doFinally(s -> probing.set(false));
    }

    /**
     * 重置回主模型（测试钩子；生产链路的回切由探测任务 {@link #probePrimaryRecovery()} 完成，
     * 粘滞设计见 architecture.md 7.1）。
     */
    void resetToPrimary() {
        activeModel.set(primary);
    }
}
