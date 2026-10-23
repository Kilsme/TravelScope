package com.travelscope.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import com.travelscope.agent.*;
import com.travelscope.config.AppProperties;
import com.travelscope.llm.*;
import com.travelscope.service.IntentCache;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class TurnCancellationTest {
    @Test void cancellationClosesRealHttpStreamAndNeverFallsBack() throws Exception {
        var firstChunk = new CountDownLatch(1);
        var disconnected = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        var fallback = mock(Model.class);
        var failure = new AtomicReference<Throwable>();
        var token = new TurnCancellation();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/chat/completions", exchange -> {
            attempts.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            String payload = "data: {\"id\":\"local-test\",\"object\":\"chat.completion.chunk\",\"model\":\"fake\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + "x".repeat(16384) + "\"},\"finish_reason\":null}]}\n\n";
            try (var body = exchange.getResponseBody()) {
                for (int i = 0; i < 500; i++) {
                    body.write(payload.getBytes(StandardCharsets.UTF_8));
                    body.flush();
                    Thread.sleep(10);
                }
            } catch (java.io.IOException closed) { disconnected.countDown(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var raw = OpenAIChatModel.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .apiKey("local-fake-key").modelName("fake").stream(true).build();
            var pool = new ModelProviderPool("test", List.of(provider("first", 1, raw), provider("fallback", 2, fallback)));
            var subscription = pool.stream(List.of(Msg.builder().role(MsgRole.USER).textContent("test").build()), List.of(), null)
                    .contextWrite(c -> c.put(TurnCancellation.KEY, token))
                    .subscribe(chunk -> firstChunk.countDown(), failure::set);
            try {
                assertTrue(firstChunk.await(5, TimeUnit.SECONDS), "must cancel during an active network response");
                token.cancel();
                assertTrue(disconnected.await(3, TimeUnit.SECONDS), "fake model server must observe the closed client connection");
                assertInstanceOf(CancellationException.class, failure.get());
                assertEquals(1, attempts.get());
                verifyNoInteractions(fallback);
            } finally { subscription.dispose(); }
        } finally { server.stop(0); }
    }

    @Test void blockingClassificationCannotRetryOrReachL3AfterCancel() throws Exception {
        var started = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        var calls = new AtomicInteger();
        Model model = new Model() {
            public String getModelName() { return "fake"; }
            public Flux<ChatResponse> stream(List<Msg> m, List<ToolSchema> t, GenerateOptions o) {
                return Flux.<ChatResponse>never().doOnSubscribe(s -> { calls.incrementAndGet(); started.countDown(); })
                        .doOnCancel(closed::countDown);
            }
        };
        var classifier = new LightweightIntentClassifier(model);
        var l3Calls = new AtomicInteger();
        var properties = new AppProperties();
        var router = new IntentCascadeRouter(properties, mock(IntentCache.class), classifier::classify,
                (m,c,u,s) -> { l3Calls.incrementAndGet(); return null; });
        var token = new TurnCancellation();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> token.call(() -> router.classify("unmatched test question", "", "1", "cancel-test")));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            token.cancel();
            var error = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, error.getCause());
            assertTrue(closed.await(1, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertEquals(0, l3Calls.get());
        }
    }

    @Test void cancelledSignalSurvivesAsyncContextAndRejectsNewWork() {
        var token = new TurnCancellation();
        var original = new com.travelscope.assessment.ObservationContext(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "trace", null, null)
                .withCancellation(token);
        assertSame(token, original.child(java.util.UUID.randomUUID()).withPurpose("SUMMARY").cancellation());
        token.cancel();
        var subscribed = new AtomicInteger();
        assertThrows(CancellationException.class, () -> token.guard(Flux.defer(() -> {
            subscribed.incrementAndGet(); return Flux.just("late");
        })).blockLast());
        assertEquals(0, subscribed.get());
    }

    private static ModelProviderPool.Provider provider(String id, int order, Model model) {
        return new ModelProviderPool.Provider(id, order, model, new ProviderCircuitBreaker(id, 60, .95, 30000), Duration.ofSeconds(10), Duration.ofSeconds(10));
    }
}
