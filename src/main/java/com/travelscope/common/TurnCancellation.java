package com.travelscope.common;

import com.travelscope.assessment.ObservationContext;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** One execution owns one signal, shared through Reactor and AgentScope contexts.
 * The scoped thread bridge is only for legacy synchronous workflow entry points. */
public final class TurnCancellation {
    public static final String KEY = "chat.turn.cancellation";
    private static final ThreadLocal<TurnCancellation> CURRENT = new ThreadLocal<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Sinks.Empty<Void> signal = Sinks.empty();

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) signal.tryEmitEmpty();
    }
    public boolean isCancelled() { return cancelled.get(); }
    public void check() { if (isCancelled()) throw new CancellationException("Turn cancelled"); }
    public <T> Flux<T> guard(Flux<T> source) {
        return Flux.defer(() -> {
            check();
            // Complete the companion first: takeUntilOther's error path does not cancel
            // the source in the bundled Reactor version. Only then report cancellation.
            return source.takeUntilOther(signal.asMono()).concatWith(Flux.defer(() -> {
                check();
                return Flux.empty();
            }));
        }).contextWrite(c -> c.put(KEY, this));
    }
    public <T> T call(Supplier<T> work) {
        TurnCancellation previous = CURRENT.get();
        CURRENT.set(this);
        try { check(); T result = work.get(); check(); return result; }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
    public static TurnCancellation from(ObservationContext observation) {
        return observation != null && observation.cancellation() != null ? observation.cancellation() : CURRENT.get();
    }
    public static TurnCancellation runtime(io.agentscope.core.agent.RuntimeContext context) {
        TurnCancellation token = context.get(KEY);
        return token != null ? token : from(context.get(ObservationContext.KEY));
    }
    public static <T> T inRuntime(io.agentscope.core.agent.RuntimeContext context, Supplier<T> work) {
        TurnCancellation token = runtime(context);
        return token == null ? work.get() : token.call(work);
    }
    public static void check(ObservationContext observation) {
        var token = from(observation);
        if (token != null) token.check();
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Execution interrupted");
    }
    public static void rethrow(Throwable error, ObservationContext observation) {
        check(observation);
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof CancellationException || cause instanceof InterruptedException)
                throw new CancellationException("Execution cancelled");
            if (cause.getCause() == cause) break;
        }
    }
    public static <T> T await(Mono<T> source, Duration timeout, ObservationContext observation) {
        check(observation);
        var token = from(observation);
        return token == null ? source.block(timeout) : token.guard(source.flux()).singleOrEmpty().block(timeout);
    }
}
