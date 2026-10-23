package com.travelscope.agent.tools;

import static org.junit.jupiter.api.Assertions.*;
import com.travelscope.common.TurnCancellation;
import com.travelscope.collab.RoutePlan;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class CollabCancellationTest {
    @Test void cancellingParallelDayCopyClosesEveryCallWithoutRetry() throws Exception {
        var tools = new CollabTools(null, null, null, null, null, null, null, null);
        var token = new TurnCancellation();
        var started = new CountDownLatch(2);
        var closed = new CountDownLatch(2);
        var calls = new AtomicInteger();
        tools.dayCopyWriter = (day, intake) -> {
            return TurnCancellation.await(Mono.<CollabTools.DayCopy>never()
                    .doOnSubscribe(s -> { calls.incrementAndGet(); started.countDown(); })
                    .doOnCancel(closed::countDown), Duration.ofSeconds(5), null);
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var task = executor.submit(() -> token.call(() -> tools.produceDayCopiesParallel(
                    List.of(new RoutePlan.RouteDay(1, null, List.of(), 0), new RoutePlan.RouteDay(2, null, List.of(), 0)), "test")));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            token.cancel();
            assertThrows(ExecutionException.class, () -> task.get(2, TimeUnit.SECONDS));
            assertTrue(closed.await(1, TimeUnit.SECONDS));
            assertEquals(2, calls.get(), "Neither day may retry after cancellation");
        }
    }
}
