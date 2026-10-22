package com.travelscope.llm;

import com.travelscope.common.TracingHelper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v5-F3 模型池（{@code implements Model} 的 N 路选路装饰器，2026-10-22）。
 * <p>
 * 框架核验依据（agentscope 2.0.3）：ReActAgent/HarnessAgent 持有 {@code final Model}，
 * 运行时换模型只能走「接口装饰器 + 内部换委托目标」（框架自带 fallbackModel 即此模式，
 * 原 LlmGatewayModel 同款）——本池把该模式从「主备 2 路」泛化为 N 路优先级选路。
 * </p>
 * <p>
 * 语义（自原 LlmGatewayModel 平移，链路行为不变）：
 * <ol>
 *   <li><b>选路</b>：priority 升序取第一个熔断器 CLOSED 的 provider；HALF_OPEN 只留给探针、
 *       OPEN 摘除。全部不可用 → {@link AllProvidersOpenException}（用户侧转
 *       「AI 服务暂时不可用，请联系管理员」）。</li>
 *   <li><b>超时分段</b>：首包 {@code firstPacketTimeout}（默认 10s，挂死快速失败）+
 *       首包后块间 {@code chunkTimeout}（默认 30s，慢生成不误杀）。超时 → 该 provider
 *       熔断器立即 OPEN（用户口径：超出规定响应时间即熔断）。</li>
 *   <li><b>首包提交闸门</b>（P0-B 语义平移）：首个非空块到达前失败（连接失败/超时/401/
 *       模型不存在）→ 计失败后 failover 池内下一个健康 provider 干净重发（用户只看到
 *       最终成功的响应）；首包后中途失败 → 已发块不可回收（Flux 语义），计失败、如实上抛
 *       不切换（避免「A 前半段 + B 全量重发」拼接输出）。</li>
 *   <li><b>能力方法</b>：全部委托当前会选路的 provider（全 OPEN 时委托 priority 首位，
 *       保持 getModelName 有值——ChatService 按它落库当轮模型名）。</li>
 * </ol>
 * </p>
 */
public class ModelProviderPool implements Model {

    private static final Logger log = LoggerFactory.getLogger(ModelProviderPool.class);

    /** 池内一个 provider：裸模型（ObservedModel(OpenAIChatModel)）+ 独立熔断器 + 超时配置 */
    public record Provider(String id, int priority, Model raw,
                           ProviderCircuitBreaker breaker,
                           Duration firstPacketTimeout, Duration chunkTimeout) {
    }

    private final String poolName;
    /** priority 升序（兜底模型给大值） */
    private final List<Provider> providers;
    /** 业务链路打点（model-call span，自 LlmGatewayModel 平移：per-provider 尝试一个 span，
     *  outcome 三态 success / fallback（首包前失败已切换）/ error） */
    private final TracingHelper tracing;

    public ModelProviderPool(String poolName, List<Provider> providers) {
        this(poolName, providers, TracingHelper.NOOP);
    }

    public ModelProviderPool(String poolName, List<Provider> providers, TracingHelper tracing) {
        this.poolName = poolName;
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt(Provider::priority))
                .toList();
        this.tracing = tracing;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                     GenerateOptions options) {
        return Flux.defer(() -> doStream(messages, tools, options, new LinkedHashSet<>()));
    }

    private Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools,
                                        GenerateOptions options, Set<String> tried) {
        Provider p = pick(tried);
        if (p == null) {
            log.error("model_pool {} 全部 provider 熔断（tried={}）", poolName, tried);
            return Flux.error(new AllProvidersOpenException(
                    poolName + " 全部 provider 熔断（tried=" + tried + "）"));
        }
        // committed 为 per-subscription 局部状态（stream() 的 Flux.defer 内逐层创建）
        AtomicBoolean committed = new AtomicBoolean();
        return Flux.defer(() -> {
            TracingHelper.SpanHandle span = tracing.startSpan("model-call",
                    Map.of("model", String.valueOf(p.raw().getModelName()), "pool", poolName));
            return Flux.defer(() -> p.raw().stream(messages, tools, options))
                    // 注意重载坑（沿袭）：timeout(Duration, lambda) 会把 lambda 误绑 fallback Publisher，
                    // 必须用 timeout(Publisher 首档, Function 后续档)
                    .timeout(Mono.delay(p.firstPacketTimeout()), chunk -> Mono.delay(p.chunkTimeout()))
                    .doOnNext(chunk -> {
                        if (chunk.getContent() != null && !chunk.getContent().isEmpty()) {
                            committed.set(true);   // 首个非空块 = 首包（role-only 空块不提交）
                        }
                    })
                    .doOnComplete(() -> {
                        span.tagIfAbsent("outcome", "success");
                        span.end();
                    })
                    // 须在 onErrorResume 上游：观测原始错误（首包前失败 = 本 provider 经切换让位）
                    .doOnError(e -> {
                        span.tag("outcome", committed.get() ? "error" : "fallback");
                        span.error(e);
                        span.end();
                    })
                    .doOnCancel(span::end)
                    .onErrorResume(e -> {
                        if (committed.get()) {
                            p.breaker().onFailure();
                            log.warn("model_pool {} provider {} 首包后失败不切换（{}: {}）",
                                    poolName, p.id(), e.getClass().getSimpleName(), e.getMessage());
                            return Flux.error(e);
                        }
                        if (e instanceof java.util.concurrent.TimeoutException) {
                            p.breaker().onTimeout();
                        } else {
                            p.breaker().onFailure();
                        }
                        log.warn("model_pool {} provider {} 首包前失败（{}: {}），failover 下一个健康 provider",
                                poolName, p.id(), e.getClass().getSimpleName(), e.getMessage());
                        tried.add(p.id());
                        return doStream(messages, tools, options, tried);
                    });
        });
    }

    /** priority 升序第一个未被 try 过且熔断器 CLOSED 的 provider；无 → null */
    private Provider pick(Set<String> tried) {
        for (Provider p : providers) {
            if (!tried.contains(p.id()) && p.breaker().routeAllowed()) {
                return p;
            }
        }
        return null;
    }

    /** 能力委托目标：当前会选路的 provider（全 OPEN 时 priority 首位） */
    private Provider effective() {
        for (Provider p : providers) {
            if (p.breaker().routeAllowed()) {
                return p;
            }
        }
        return providers.get(0);
    }

    @Override
    public String getModelName() {
        return effective().raw().getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return effective().raw().supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        // 显式委托（框架内置 ReActAgent$2 包装器漏了此方法——失真坑不重踩）
        return effective().raw().supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return effective().raw().getContextWindowSize();
    }

    public List<Provider> providers() {
        return providers;
    }

    public String poolName() {
        return poolName;
    }

    /** 对指定 provider 发最小探测请求（探针调度专用；成功返回/失败抛异常，超时按 chunkTimeout） */
    public static void probe(Provider p) {
        p.raw().stream(List.of(Msg.builder().role(MsgRole.USER).textContent("ping").build()),
                        List.of(), null)
                .timeout(p.chunkTimeout())
                .blockLast();
    }
}
