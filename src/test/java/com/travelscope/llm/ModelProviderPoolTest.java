package com.travelscope.llm;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.embedding.EmbeddingModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v5-F3 模型池单测（六剧本，2026-10-22）：
 * <ol>
 *   <li>单次响应超时 → 该 provider <b>立即 OPEN</b>（不等统计达标）+ failover；</li>
 *   <li>统计口径——总调用 ≤ minCalls 不熔断；&gt; minCalls 且成功率 &lt; 95% 才 OPEN；</li>
 *   <li>探针成功 → CLOSED + 计数清零 + 回选路；</li>
 *   <li>探针失败 → 继续 OPEN 重新计时；</li>
 *   <li>池内全 OPEN → {@link AllProvidersOpenException}（chat + embedding 两池）；</li>
 *   <li>priority 选路 + 首包提交闸门（首包前失败干净切换 / 首包后失败如实上抛不切换）。</li>
 * </ol>
 * 桩与超时参数沿袭旧 LlmGatewayModelTest 同款形态（挂死用 Flux.never() + 首包超时 150ms 掐断，
 * 真实等待仅在超时档，单测耗时可控）。
 */
class ModelProviderPoolTest {

    /** 计数桩：可控挂死 / 首包前失败 / 首包后中途失败，记录调用次数 */
    static class CountingModel implements Model {
        final String name;
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean failNext;       // 首包前失败（模拟 401/连接失败）
        volatile boolean failMidStream;  // 发 1 个有内容块后失败（首包已提交）
        volatile boolean hang;           // 挂死无响应（Flux.never，由池的首包超时掐断）

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
                ChatResponse chunk = ChatResponse.builder().id("c")
                        .content(List.of(TextBlock.builder().text("partial-").build()))
                        .build();
                return Flux.just(chunk).concatWith(Flux.error(new RuntimeException("mid-stream failure")));
            }
            if (hang) {
                return Flux.never();
            }
            return Flux.just(ChatResponse.builder().id("ok-" + name)
                    .content(List.of(TextBlock.builder().text("resp-" + name).build()))
                    .build());
        }

        @Override
        public String getModelName() {
            return name;
        }
    }

    /** embedding 计数桩：可控失败，记录调用次数 */
    static class CountingEmbedding implements EmbeddingModel {
        final String name;
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean failNext;

        CountingEmbedding(String name) {
            this.name = name;
        }

        @Override
        public Mono<double[]> embed(ContentBlock content) {
            calls.incrementAndGet();
            return failNext ? Mono.error(new RuntimeException("simulated embed failure"))
                    : Mono.just(new double[]{0.1, 0.2, 0.3});
        }

        @Override
        public String getModelName() {
            return name;
        }

        @Override
        public int getDimensions() {
            return 3;
        }
    }

    private static ProviderCircuitBreaker breaker(String id) {
        return new ProviderCircuitBreaker(id, 60, 0.95, 30_000);
    }

    /** 双 provider 池（a=priority 1，b=priority 2），首包超时可调 */
    private static ModelProviderPool pool(CountingModel a, CountingModel b, Duration firstPacket,
                                          ProviderCircuitBreaker ab, ProviderCircuitBreaker bb) {
        return new ModelProviderPool("test", List.of(
                new ModelProviderPool.Provider("a", 1, a, ab, firstPacket, Duration.ofSeconds(2)),
                new ModelProviderPool.Provider("b", 2, b, bb, firstPacket, Duration.ofSeconds(2))));
    }

    private static List<Msg> ping() {
        return List.of(Msg.builder().role(MsgRole.USER).textContent("ping").build());
    }

    private static boolean hasContent(List<ChatResponse> responses) {
        return responses.stream().anyMatch(r -> r.getContent() != null && !r.getContent().isEmpty());
    }

    // ---------- 剧本①：单次响应超时 → 立即 OPEN + failover ----------

    @Test
    @DisplayName("剧本① 超时→立即 OPEN：挂死 provider 被首包超时掐断，熔断立即 OPEN，流量切下一个健康 provider")
    void timeoutTripsImmediatelyAndFailovers() {
        CountingModel a = new CountingModel("a");
        a.hang = true;
        CountingModel b = new CountingModel("b");
        ProviderCircuitBreaker ab = breaker("test/a");
        ModelProviderPool pool = pool(a, b, Duration.ofMillis(150), ab, breaker("test/b"));

        List<ChatResponse> out = pool.stream(ping(), List.of(), null)
                .collectList().block(Duration.ofSeconds(5));

        assertNotNull(out, "failover 后应有响应（用户不白屏）");
        assertTrue(hasContent(out), "健康 provider 应产出实际内容");
        assertEquals(1, a.calls.get());
        assertEquals(1, b.calls.get());
        // 用户口径：超出规定响应时间即熔断——不等统计达标，立即 OPEN
        assertEquals(ProviderCircuitBreaker.State.OPEN, ab.get());
    }

    // ---------- 剧本②：统计口径（总调用 > 60 且成功率 < 95% 才 OPEN） ----------

    @Test
    @DisplayName("剧本② 统计口径：≤60 次失败不熔断；>60 且成功率<95% 才 OPEN；成功率≥95% 不熔断")
    void statsTripRespectsMinCallsAndSuccessRate() {
        ProviderCircuitBreaker cb = breaker("stats");
        for (int i = 0; i < 60; i++) {
            cb.onFailure();
        }
        assertEquals(ProviderCircuitBreaker.State.CLOSED, cb.get(), "total=60 未超门槛，普通失败不熔断");
        cb.onFailure();
        assertEquals(ProviderCircuitBreaker.State.OPEN, cb.get(), "total=61 且成功率 0 → OPEN");

        // 边界：61 次里 3 次失败（成功率 58/61≈95.1% ≥ 95%）不熔断；第 4 次失败（58/62≈93.5%）熔断
        ProviderCircuitBreaker cb2 = breaker("stats2");
        for (int i = 0; i < 58; i++) {
            cb2.onSuccess();
        }
        for (int i = 0; i < 3; i++) {
            cb2.onFailure();
        }
        assertEquals(ProviderCircuitBreaker.State.CLOSED, cb2.get(), "成功率 ≥ 95% 不熔断");
        cb2.onFailure();
        assertEquals(ProviderCircuitBreaker.State.OPEN, cb2.get(), "成功率跌破 95% 且 total>60 → OPEN");
    }

    // ---------- 剧本③：探针成功 → CLOSE + 清零 + 回选路 ----------

    @Test
    @DisplayName("剧本③ 探针成功→CLOSE+计数清零回选路；恢复后流量回到高优先级 provider")
    void probeSuccessClosesResetsAndRoutesBack() throws InterruptedException {
        ProviderCircuitBreaker cb = new ProviderCircuitBreaker("probe-ok", 60, 0.95, 80);
        cb.onTimeout();
        assertEquals(ProviderCircuitBreaker.State.OPEN, cb.get());

        Thread.sleep(120);   // 冷却 80ms 到期
        assertTrue(cb.tryEnterHalfOpenIfCooldownExpired(System.currentTimeMillis()), "冷却到期应获得探测权");
        assertEquals(ProviderCircuitBreaker.State.HALF_OPEN, cb.get());
        assertFalse(cb.routeAllowed(), "HALF_OPEN 只放探针，不放业务流量");

        cb.onSuccess();   // 探测通过
        assertEquals(ProviderCircuitBreaker.State.CLOSED, cb.get());
        assertEquals(0, cb.total(), "计数清零重新累计");
        assertEquals(0, cb.successCount());
        assertTrue(cb.routeAllowed(), "恢复选路");

        // 池级验证：a 超时熔断 → 流量切 b；探针恢复 a 后流量回到 a（priority 高）
        CountingModel a = new CountingModel("a");
        a.hang = true;
        CountingModel b = new CountingModel("b");
        ProviderCircuitBreaker ab = new ProviderCircuitBreaker("test/a", 60, 0.95, 80);
        ModelProviderPool pool = pool(a, b, Duration.ofMillis(150), ab, breaker("test/b"));
        assertTrue(hasContent(pool.stream(ping(), List.of(), null).collectList().block(Duration.ofSeconds(5))));
        assertEquals(ProviderCircuitBreaker.State.OPEN, ab.get());
        assertEquals("b", pool.getModelName(), "OPEN 期间能力委托次选 provider");

        Thread.sleep(120);
        assertTrue(ab.tryEnterHalfOpenIfCooldownExpired(System.currentTimeMillis()));
        ab.onSuccess();   // 模拟探针成功
        a.hang = false;
        assertTrue(hasContent(pool.stream(ping(), List.of(), null).collectList().block(Duration.ofSeconds(5))));
        assertEquals(2, a.calls.get(), "恢复后流量回到 priority 1");
        assertEquals("a", pool.getModelName());
    }

    // ---------- 剧本④：探针失败 → 继续 OPEN 重新计时 ----------

    @Test
    @DisplayName("剧本④ 探针失败→继续 OPEN 重新计时（冷却未到期不得再探）")
    void probeFailureReopensWithFreshCooldown() throws InterruptedException {
        ProviderCircuitBreaker cb = new ProviderCircuitBreaker("probe-fail", 60, 0.95, 80);
        cb.onTimeout();
        Thread.sleep(120);
        assertTrue(cb.tryEnterHalfOpenIfCooldownExpired(System.currentTimeMillis()));

        cb.onFailure();   // 探测失败
        assertEquals(ProviderCircuitBreaker.State.OPEN, cb.get());
        assertFalse(cb.tryEnterHalfOpenIfCooldownExpired(System.currentTimeMillis()),
                "冷却重新计时，立即再探被拒");
        Thread.sleep(120);
        assertTrue(cb.tryEnterHalfOpenIfCooldownExpired(System.currentTimeMillis()),
                "新一轮冷却到期可再探");
    }

    // ---------- 剧本⑤：全灭 → AllProvidersOpenException ----------

    @Test
    @DisplayName("剧本⑤ 全 OPEN→AllProvidersOpenException，且选路摘除（零真实调用）")
    void allOpenThrowsAllProvidersOpen() {
        CountingModel a = new CountingModel("a");
        CountingModel b = new CountingModel("b");
        ProviderCircuitBreaker ab = breaker("test/a");
        ProviderCircuitBreaker bb = breaker("test/b");
        ab.onTimeout();
        bb.onTimeout();
        ModelProviderPool pool = pool(a, b, Duration.ofSeconds(1), ab, bb);

        assertThrows(AllProvidersOpenException.class,
                () -> pool.stream(ping(), List.of(), null).collectList().block(Duration.ofSeconds(5)));
        assertEquals(0, a.calls.get(), "全 OPEN 时选路摘除，不再打真实模型");
        assertEquals(0, b.calls.get());
    }

    @Test
    @DisplayName("剧本⑤-embedding 池：首 provider 失败 failover；全灭抛 AllProvidersOpenException")
    void embeddingPoolFailoversAndThrowsWhenAllOpen() {
        CountingEmbedding a = new CountingEmbedding("emb-a");
        a.failNext = true;
        CountingEmbedding b = new CountingEmbedding("emb-b");
        EmbeddingProviderPool pool = new EmbeddingProviderPool(List.of(
                new EmbeddingProviderPool.EmbeddingProvider("a", a, breaker("emb/a"), Duration.ofSeconds(1)),
                new EmbeddingProviderPool.EmbeddingProvider("b", b, breaker("emb/b"), Duration.ofSeconds(1))));

        double[] out = pool.embed(TextBlock.builder().text("ping").build()).block(Duration.ofSeconds(5));
        assertNotNull(out);
        assertEquals(1, a.calls.get());
        assertEquals(1, b.calls.get());

        // 全灭：两个 breaker 都 OPEN → AllProvidersOpenException（RagServiceImpl 既有 catch 退化 BM25）
        CountingEmbedding c = new CountingEmbedding("emb-c");
        CountingEmbedding d = new CountingEmbedding("emb-d");
        EmbeddingProviderPool allOpen = new EmbeddingProviderPool(List.of(
                new EmbeddingProviderPool.EmbeddingProvider("c", c, breaker("emb/c"), Duration.ofSeconds(1)),
                new EmbeddingProviderPool.EmbeddingProvider("d", d, breaker("emb/d"), Duration.ofSeconds(1))));
        allOpen.providers().forEach(p -> p.breaker().onTimeout());
        assertThrows(AllProvidersOpenException.class,
                () -> allOpen.embed(TextBlock.builder().text("ping").build()).block(Duration.ofSeconds(5)));
        assertEquals(0, c.calls.get());
        assertEquals(0, d.calls.get());
    }

    // ---------- 剧本⑥：priority 选路 + 首包提交闸门 ----------

    @Test
    @DisplayName("剧本⑥-a priority 选路：双健康时全部流量走 priority 小者；OPEN 者被摘除")
    void priorityRoutingAndOpenSkip() {
        CountingModel a = new CountingModel("a");
        CountingModel b = new CountingModel("b");
        ProviderCircuitBreaker ab = breaker("test/a");
        ModelProviderPool pool = pool(a, b, Duration.ofSeconds(1), ab, breaker("test/b"));

        for (int i = 0; i < 3; i++) {
            assertTrue(hasContent(pool.stream(ping(), List.of(), null).collectList().block(Duration.ofSeconds(5))));
        }
        assertEquals(3, a.calls.get(), "双健康时全部流量走 priority 1");
        assertEquals(0, b.calls.get());
        assertEquals("a", pool.getModelName());

        ab.onTimeout();   // a 熔断 → 摘除，直接路由 b（a 零新增调用）
        int aCallsBefore = a.calls.get();
        assertTrue(hasContent(pool.stream(ping(), List.of(), null).collectList().block(Duration.ofSeconds(5))));
        assertEquals(aCallsBefore, a.calls.get());
        assertEquals(1, b.calls.get());
    }

    @Test
    @DisplayName("剧本⑥-b 首包闸门：首包前失败干净 failover 重发；首包后失败如实上抛不切换（无拼接输出）")
    void firstPacketCommitGate() {
        // 首包前失败（401）：切换重发，用户只看到次选 provider 的完整响应
        CountingModel a = new CountingModel("a");
        a.failNext = true;
        CountingModel b = new CountingModel("b");
        ModelProviderPool pool = pool(a, b, Duration.ofSeconds(1), breaker("test/a"), breaker("test/b"));
        List<ChatResponse> out = pool.stream(ping(), List.of(), null).collectList().block(Duration.ofSeconds(5));
        assertNotNull(out);
        assertTrue(hasContent(out));
        assertEquals(1, a.calls.get());
        assertEquals(1, b.calls.get());

        // 首包后中途失败：已发块不可回收，不切换（避免「A 前半段 + B 全量重发」拼接）
        CountingModel c = new CountingModel("c");
        c.failMidStream = true;
        CountingModel d = new CountingModel("d");
        ModelProviderPool pool2 = pool(c, d, Duration.ofSeconds(1), breaker("test/c"), breaker("test/d"));
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> pool2.stream(ping(), List.of(), null).collectList().block(Duration.ofSeconds(5)));
        assertEquals("mid-stream failure", error.getMessage());
        assertEquals(1, c.calls.get());
        assertEquals(0, d.calls.get(), "首包后失败不切换");
    }
}
