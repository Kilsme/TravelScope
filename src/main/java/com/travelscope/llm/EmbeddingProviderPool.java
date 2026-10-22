package com.travelscope.llm;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * v5-F3 向量模型池（{@code implements EmbeddingModel} 的主备选路装饰器 + per-provider 熔断，
 * 2026-10-22）。provider 列表顺序即优先级；单次 embed 超时（默认 15s，对齐 RAG 检索通道
 * 超时档）→ 该 provider 立即 OPEN；失败走统计口径（同 chat 池）。全部 provider 熔断 →
 * {@link AllProvidersOpenException}——RagServiceImpl 既有 catch 把语义路本轮退化为
 * BM25 关键词检索（零新代码路径），不阻断对话主流程。
 * <p>
 * 当前列表只有 DashScope text-embedding-v3 一项（配置留扩展位，后续加备选只改
 * travelscope.model-pool.embedding.providers）。
 * </p>
 */
public class EmbeddingProviderPool implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingProviderPool.class);

    public record EmbeddingProvider(String id, EmbeddingModel raw,
                                    ProviderCircuitBreaker breaker, Duration timeout) {
    }

    private final List<EmbeddingProvider> providers;

    public EmbeddingProviderPool(List<EmbeddingProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    @Override
    public Mono<double[]> embed(ContentBlock content) {
        return Mono.defer(() -> doEmbed(content, new LinkedHashSet<>()));
    }

    private Mono<double[]> doEmbed(ContentBlock content, Set<String> tried) {
        EmbeddingProvider p = pick(tried);
        if (p == null) {
            log.error("embedding_pool 全部 provider 熔断（tried={}），本轮语义路退化 BM25", tried);
            return Mono.error(new AllProvidersOpenException(
                    "embedding 池全部 provider 熔断（tried=" + tried + "）"));
        }
        return p.raw().embed(content)
                .timeout(p.timeout())
                .doOnSuccess(v -> p.breaker().onSuccess())
                .onErrorResume(e -> {
                    if (e instanceof java.util.concurrent.TimeoutException) {
                        p.breaker().onTimeout();
                    } else {
                        p.breaker().onFailure();
                    }
                    log.warn("embedding_pool provider {} 失败（{}: {}），failover 下一个",
                            p.id(), e.getClass().getSimpleName(), e.getMessage());
                    tried.add(p.id());
                    return doEmbed(content, tried);
                });
    }

    private EmbeddingProvider pick(Set<String> tried) {
        for (EmbeddingProvider p : providers) {
            if (!tried.contains(p.id()) && p.breaker().routeAllowed()) {
                return p;
            }
        }
        return null;
    }

    @Override
    public String getModelName() {
        return effective().raw().getModelName();
    }

    @Override
    public int getDimensions() {
        return effective().raw().getDimensions();
    }

    private EmbeddingProvider effective() {
        for (EmbeddingProvider p : providers) {
            if (p.breaker().routeAllowed()) {
                return p;
            }
        }
        return providers.get(0);
    }

    public List<EmbeddingProvider> providers() {
        return providers;
    }

    /** 对指定 provider 发最小探测请求（探针调度专用） */
    public static void probe(EmbeddingProvider p) {
        p.raw().embed(TextBlock.builder().text("ping").build())
                .timeout(p.timeout())
                .block();
    }
}
