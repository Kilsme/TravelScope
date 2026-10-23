package com.travelscope.chat;

import com.alibaba.fastjson2.JSON;
import com.travelscope.dto.ChatEvent;
import com.travelscope.dto.ChatRequest;
import com.travelscope.service.ChatService;
import com.travelscope.service.ConversationService;
import com.travelscope.service.MinioService;

import io.micrometer.core.instrument.MeterRegistry;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.Disposables;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ChatTurnService {
    private static final Logger log = LoggerFactory.getLogger(ChatTurnService.class);
    private final ChatTurnStore store;
    private final ChatService chat;
    private final ConversationService conversations;
    private final MinioService images;
    private final ReconnectProperties config;
    private final ConcurrentMap<String, Running> running = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService flushers =
            Executors.newScheduledThreadPool(
                    2, Thread.ofPlatform().daemon().name("turn-flush-", 0).factory());

    @Autowired(required = false)
    private MeterRegistry metrics;

    private volatile boolean ready;

    public ChatTurnService(
            ChatTurnStore store,
            ChatService chat,
            ConversationService conversations,
            MinioService images,
            ReconnectProperties config) {
        this.store = store;
        this.chat = chat;
        this.conversations = conversations;
        this.images = images;
        this.config = config;
    }

    private String key(long user, UUID id) {
        return user + ":" + id;
    }

    private void count(String name) {
        if (metrics != null) metrics.counter("chat.turn." + name).increment();
    }

    private void enabled() {
        if (!config.isEnabled() || !ready)
            throw new TurnException(
                    HttpStatus.SERVICE_UNAVAILABLE, "RECONNECT_NOT_READY", "对话恢复服务尚未准备好");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        if (!config.isEnabled()) return;
        try {
            for (var turn : store.active()) {
                store.finish(
                        turn.userId(),
                        turn.turnId(),
                        "INTERRUPTED",
                        "INTERRUPTED",
                        null,
                        "SERVER_RESTARTED",
                        "服务已重启，本轮已中断；已有内容已保留",
                        null,
                        null);
                count("restart_interrupted");
            }
            ready = true;
        } catch (Exception e) {
            throw new IllegalStateException("断线恢复初始化失败，请先执行 chat-reconnect-upgrade.sql", e);
        }
    }

    public ChatTurn submit(long user, ChatRequest request) {
        enabled();
        if (request.getTurnId() == null)
            throw new TurnException(HttpStatus.BAD_REQUEST, "TURN_ID_REQUIRED", "缺少本轮标识");
        byte[] bytes = null;
        String type = null;
        try {
            if (request.getImageBase64() != null && !request.getImageBase64().isBlank()) {
                bytes = ChatService.decodeImage(request.getImageBase64());
                if (bytes.length > 1_500_000) throw new IllegalArgumentException("图片超过 1.5MB");
                type = ChatService.sniffImageMediaType(bytes);
            }
        } catch (IllegalArgumentException e) {
            throw new TurnException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", e.getMessage());
        }
        String message = Objects.toString(request.getMessage(), "");
        if (type == null && message.isBlank())
            throw new TurnException(HttpStatus.BAD_REQUEST, "EMPTY_MESSAGE", "消息不能为空");
        String locale;
        try { locale = com.travelscope.common.ReplyLanguage.normalize(request.getLocale()); }
        catch (IllegalArgumentException e) { throw new TurnException(HttpStatus.BAD_REQUEST, "INVALID_LOCALE", "Unsupported locale"); }
        var previous = store.find(user, request.getTurnId());
        var original = new ArrayList<Object>(Arrays.asList(request.getConversationId(), message, request.getSourceTurnId(), bytes == null ? null : hash(bytes)));
        if (previous.isEmpty() || previous.get().requestHashVersion() >= 2) original.add(locale);
        String hash =
                hash(
                        JSON.toJSONString(
                                original));
        String question = type != null && message.isBlank() ? "讲解一下这个景点" : message;
        boolean duplicate = store.find(user, request.getTurnId()).isPresent();
        ChatTurn t = store.reserve(user, request, hash, question, type);
        if (duplicate) count("duplicate");
        if (!"PREPARING".equals(t.status())) return t;
        log.info(
                "turn_accept userId={} conversationId={} turnId={}",
                user,
                t.conversationId(),
                t.turnId());
        if (t.imageKey() != null) {
            try {
                images.upload(t.imageKey(), new ByteArrayInputStream(bytes), bytes.length, type);
            } catch (Exception e) {
                count("image_prepare_failure");
                throw new TurnException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "IMAGE_PREPARATION_FAILED",
                        "图片暂未保存，请恢复网络后重试原消息");
            }
        }
        t = store.prepare(user, t.turnId());
        if ("QUEUED".equals(t.status())) launch(t);
        return t;
    }

    public ChatTurn get(long user, UUID id) {
        enabled();
        return store.owned(user, id);
    }

    public List<ChatTurn> list(long user, long conversation, boolean activeOnly) {
        enabled();
        return store.list(user, conversation, activeOnly);
    }

    private void launch(ChatTurn turn) {
        Running r = new Running(turn);
        if (running.putIfAbsent(key(turn.userId(), turn.turnId()), r) != null) return;
        workers.execute(
                () -> {
                    try {
                        if (!store.claim(turn.userId(), turn.turnId())) {
                            running.remove(key(turn.userId(), turn.turnId()), r);
                            return;
                        }
                        count("execution");
                        log.info(
                                "turn_execute userId={} conversationId={} turnId={}",
                                turn.userId(),
                                turn.conversationId(),
                                turn.turnId());
                        String base64 = null;
                        if (turn.imageKey() != null)
                            try (var in = images.download(turn.imageKey())) {
                                byte[] bytes = in.readNBytes(1_500_001);
                                if (bytes.length > 1_500_000)
                                    throw new IllegalStateException(
                                            "Stored image exceeds admitted size");
                                base64 = Base64.getEncoder().encodeToString(bytes);
                            }
                        if (r.disposable.isDisposed()) {
                            terminal(r, "CANCELLED", null);
                            return;
                        }
                        var conversation =
                                conversations.getOwnedConversation(
                                        turn.conversationId(), turn.userId());
                        r.execution =
                                chat.execution(
                                        conversation,
                                        turn.userId(),
                                        turn.inputText(),
                                        base64,
                                        turn.turnId(),
                                        turn.responseLocale(),
                                        cost -> store.charge(turn.userId(), turn.turnId(), cost));
                        if (r.cancelRequested || r.finished) {
                            r.execution.cancel();
                            terminal(r, "CANCELLED", null);
                            return;
                        }
                        r.flushTask =
                                flushers.scheduleAtFixedRate(
                                        () -> {
                                            try {
                                                r.flush();
                                            } catch (Exception e) {
                                                terminal(r, "FAILED", e);
                                                r.disposable.dispose();
                                            }
                                        },
                                        50,
                                        50,
                                        TimeUnit.MILLISECONDS);
                        var disposable =
                                r.execution
                                        .events
                                        .takeUntilOther(
                                                reactor.core.publisher.Mono.delay(
                                                                Duration.ofSeconds(
                                                                        config
                                                                                .getExecutionSeconds()))
                                                        .flatMap(
                                                                ignored ->
                                                                        reactor.core.publisher.Mono
                                                                                .error(
                                                                                        new TimeoutException(
                                                                                                "整轮执行超时"))))
                                        .publishOn(Schedulers.boundedElastic(), 32)
                                        .doFinally(
                                                signal -> {
                                                    if (signal == SignalType.CANCEL)
                                                        terminal(
                                                                r,
                                                                r.cancelRequested
                                                                        ? "CANCELLED"
                                                                        : "FAILED",
                                                                r.cancelRequested
                                                                        ? null
                                                                        : new IllegalStateException(
                                                                                "输出流已停止"));
                                                })
                                        .subscribe(
                                                r::event,
                                                e -> terminal(r, "FAILED", e),
                                                () -> terminal(r, "SUCCEEDED", null));
                        r.disposable.update(disposable);
                    } catch (Exception e) {
                        terminal(r, "FAILED", e);
                    }
                });
    }

    public ChatTurn cancel(long user, UUID id) {
        enabled();
        ChatTurn t = store.requestCancel(user, id);
        if (!t.active()) return t;
        Running r = running.get(key(user, id));
        if (r == null)
            return store.finish(
                    user, id, "CANCELLED", "CANCELLED", null, "CANCELLED", "已停止本轮任务", null, null);
        r.cancelRequested = true;
        r.cancelStarted = System.nanoTime();
        log.info("turn_cancel_requested userId={} conversationId={} turnId={}", user, t.conversationId(), id);
        if (r.execution != null) r.execution.cancel();
        r.disposable.dispose();
        if (r.execution == null) terminal(r, "CANCELLED", null);
        return store.owned(user, id);
    }

    private void terminal(Running r, String status, Throwable error) {
        if (r.cancelRequested) { status = "CANCELLED"; error = null; }
        synchronized (r) {
            if (r.finished || r.pending != null) return;
            try {
                r.flush();
            } catch (Exception e) {
                status = "FAILED";
                error = e;
            }
            String code = null, message = null;
            if ("CANCELLED".equals(status)) {
                code = "CANCELLED";
                message = "已停止本轮任务";
            } else if (error != null) {
                code =
                        error instanceof ChatService.ExecutionFailure f
                                ? f.code
                                : "EXECUTION_FAILED";
                message = chat.executionError(error);
            }
            ChatService.TurnResult result;
            try {
                result =
                        r.execution == null
                                ? new ChatService.TurnResult("", "FAILED", null, null)
                                : chat.executionResult(r.execution);
            } catch (Exception e) {
                status = "FAILED";
                code = "FINALIZATION_FAILED";
                message = "任务收尾失败，已保存内容将保留";
                result = new ChatService.TurnResult("", "FAILED", null, null);
            }
            r.pending = new Finalization(status, result, code, message);
            persistTerminal(r);
        }
    }

    private void persistTerminal(Running r) {
        synchronized (r) {
            if (r.finished || r.pending == null) return;
            try {
                var p = r.pending;
                ChatTurn saved =
                        store.finish(
                                r.turn.userId(),
                                r.turn.turnId(),
                                p.status,
                                p.result.outcome(),
                                p.result.content(),
                                p.code,
                                p.message,
                                p.result.tokens(),
                                p.result.model());
                r.finished = true;
                if (r.flushTask != null) r.flushTask.cancel(false);
                running.remove(key(r.turn.userId(), r.turn.turnId()), r);
                wake(r.turn.turnId());
                count("terminal." + saved.status().toLowerCase(Locale.ROOT));
                if (metrics != null && r.cancelStarted != 0)
                    metrics.timer("chat.turn.cancel_duration")
                            .record(System.nanoTime() - r.cancelStarted, TimeUnit.NANOSECONDS);
                log.info(
                        "turn_terminal userId={} conversationId={} turnId={} status={}",
                        saved.userId(),
                        saved.conversationId(),
                        saved.turnId(),
                        saved.status());
                if (r.execution != null)
                    try {
                        chat.executionFinished(r.execution, saved.status());
                    } catch (Exception e) {
                        log.warn("任务观测收尾失败 turnId={}", r.turn.turnId(), e);
                    }
            } catch (Exception e) {
                count("persistence_failure");
                log.error("任务终态暂未保存 userId={} turnId={}，将重试", r.turn.userId(), r.turn.turnId(), e);
            }
        }
    }

    private record Finalization(
            String status, ChatService.TurnResult result, String code, String message) {}

    private final class Running {
        final ChatTurn turn;
        final reactor.core.Disposable.Swap disposable = Disposables.swap();
        final StringBuilder pendingDelta = new StringBuilder();
        volatile ChatService.TurnExecution execution;
        ScheduledFuture<?> flushTask;
        volatile boolean finished;
        volatile boolean cancelRequested;
        volatile long cancelStarted;
        volatile Finalization pending;

        Running(ChatTurn turn) {
            this.turn = turn;
        }

        synchronized void event(ChatEvent event) {
            if (finished || pending != null) return;
            if ("delta".equals(event.getType())) {
                pendingDelta.append(event.getPayload());
                if (pendingDelta.toString().getBytes(StandardCharsets.UTF_8).length >= 4096)
                    flush();
            } else {
                flush();
                append(
                        event.getType(),
                        TurnSnapshot.publicPayload(event.getType(), event.getPayload()));
                wake(turn.turnId());
            }
        }

        synchronized void flush() {
            if (pendingDelta.isEmpty()) return;
            append("delta", pendingDelta.toString());
            pendingDelta.setLength(0);
            wake(turn.turnId());
        }

        private void append(String type, Object payload) {
            try {
                store.append(turn.userId(), turn.turnId(), type, payload);
            } catch (org.springframework.dao.DataAccessException error) {
                count("persistence_failure");
                log.error(
                        "turn_persistence_failed userId={} conversationId={} turnId={}",
                        turn.userId(),
                        turn.conversationId(),
                        turn.turnId());
                throw error;
            }
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        if (!ready) return;
        try {
            for (Running r : running.values()) if (r.pending != null) persistTerminal(r);
            for (ChatTurn t : store.active()) {
                long age =
                        Duration.between(
                                        "QUEUED".equals(t.status()) ? t.updatedAt() : t.createdAt(),
                                        Instant.now())
                                .toSeconds();
                if ("PREPARING".equals(t.status()) && age > config.getPreparationSeconds())
                    store.finish(
                            t.userId(),
                            t.turnId(),
                            "FAILED",
                            "FAILED",
                            null,
                            "PREPARATION_TIMEOUT",
                            "准备消息超时，请重新发送",
                            null,
                            null);
                else if ("QUEUED".equals(t.status())) {
                    if (age > config.getQueueSeconds())
                        store.finish(
                                t.userId(),
                                t.turnId(),
                                "FAILED",
                                "FAILED",
                                null,
                                "QUEUE_TIMEOUT",
                                "任务排队超时，请稍后重试",
                                null,
                                null);
                    else launch(t);
                }
            }
            subscriptions.values().forEach(Subscription::schedule);
        } catch (Exception e) {
            log.warn("任务调度/恢复存储暂不可用", e);
        }
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    public void retain() {
        if (!ready) return;
        store.expire(config.getRetentionHours());
        for (ChatTurn t : store.orphanImages())
            try {
                images.delete(t.imageKey());
                store.imageCleaned(t);
            } catch (Exception e) {
                log.warn("未引用图片清理失败 turnId={}", t.turnId(), e);
            }
    }

    public SseEmitter subscribe(long user, UUID id, long after, boolean legacy) {
        enabled();
        ChatTurn t = store.owned(user, id);
        try {
            store.validateCursor(t, after);
        } catch (TurnException error) {
            count("recovery_failure." + error.code.toLowerCase(Locale.ROOT));
            throw error;
        }
        if (subscriptions.size() >= 1000
                || subscriptions.values().stream().filter(s -> s.user == user).count() >= 8)
            throw new TurnException(HttpStatus.TOO_MANY_REQUESTS, "STREAM_LIMIT", "连接过多，请关闭重复页面");
        var s = new Subscription(user, id, after, legacy, t.lastSeq());
        subscriptions.put(s.connection, s);
        s.emitter.onCompletion(s::close);
        s.emitter.onError(e -> s.close());
        s.emitter.onTimeout(
                () -> {
                    s.close();
                    s.emitter.complete();
                });
        s.schedule();
        return s.emitter;
    }

    private void wake(UUID id) {
        subscriptions.values().stream()
                .filter(s -> s.id.equals(id))
                .forEach(Subscription::schedule);
    }

    private final class Subscription {
        final UUID connection = UUID.randomUUID(), id;
        final long user;
        final boolean legacy;
        final long replayWatermark, started = System.nanoTime();
        boolean firstDelivery = true;
        final SseEmitter emitter =
                new SseEmitter(Duration.ofSeconds(config.getSseTimeoutSeconds()).toMillis());
        final AtomicBoolean scheduled = new AtomicBoolean(), closed = new AtomicBoolean();
        long cursor, lastSend = System.nanoTime();

        Subscription(long user, UUID id, long after, boolean legacy, long replayWatermark) {
            this.user = user;
            this.id = id;
            cursor = after;
            this.legacy = legacy;
            this.replayWatermark = replayWatermark;
        }

        void schedule() {
            if (!closed.get() && scheduled.compareAndSet(false, true))
                senders.execute(
                        () -> {
                            try {
                                drain();
                            } finally {
                                scheduled.set(false);
                            }
                        });
        }

        void close() {
            closed.set(true);
            subscriptions.remove(connection);
        }

        void drain() {
            try {
                for (var e : store.events(user, id, cursor)) {
                    if (closed.get()) return;
                    String type = e.get("type").toString();
                    Object payload = e.get("payload");
                    if (legacy && "terminal".equals(type)) {
                        var terminal = (Map<?, ?>) payload;
                        if ("SUCCEEDED".equals(terminal.get("status"))) {
                            type = "done";
                            payload = terminal.get("content");
                        } else {
                            type = "error";
                            payload =
                                    terminal.get("error") instanceof Map<?, ?> error
                                            ? error.get("message")
                                            : "本轮已中断";
                        }
                    }
                    Object data =
                            legacy
                                    ? (payload instanceof String
                                            ? payload
                                            : JSON.toJSONString(
                                                    payload,
                                                    com.alibaba.fastjson2.JSONWriter.Feature
                                                            .WriteMapNullValue))
                                    : JSON.toJSONString(
                                            e,
                                            com.alibaba.fastjson2.JSONWriter.Feature
                                                    .WriteMapNullValue);
                    var frame = SseEmitter.event().id(e.get("seq").toString()).name(type);
                    // Explicit bytes avoid a servlet converter's default ISO-8859-1 charset.
                    // Split legacy multiline text into separate SSE data fields.
                    for (String line : data.toString().split("\n", -1))
                        frame.data(
                                line.getBytes(StandardCharsets.UTF_8),
                                org.springframework.http.MediaType.TEXT_PLAIN);
                    emitter.send(frame);
                    cursor = ((Number) e.get("seq")).longValue();
                    lastSend = System.nanoTime();
                    count("event_delivered");
                    if (cursor <= replayWatermark) count("event_replayed");
                    if (firstDelivery) {
                        firstDelivery = false;
                        if (metrics != null)
                            metrics.timer("chat.turn.recovery_duration")
                                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                    }
                    if ("terminal".equals(e.get("type"))) {
                        close();
                        emitter.complete();
                        return;
                    }
                }
                ChatTurn t = store.owned(user, id);
                if (!t.active() && cursor >= t.lastSeq()) {
                    close();
                    emitter.complete();
                    return;
                }
                if (System.nanoTime() - lastSend > Duration.ofSeconds(15).toNanos()) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    lastSend = System.nanoTime();
                }
            } catch (Exception e) {
                close();
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String hash(String value) {
        return hash(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @PreDestroy
    public void close() {
        ready = false;
        subscriptions
                .values()
                .forEach(
                        s -> {
                            s.close();
                            s.emitter.complete();
                        });
        running.values()
                .forEach(
                        r -> {
                            terminal(r, "INTERRUPTED", new IllegalStateException("服务正在重启，本轮已中断"));
                            r.disposable.dispose();
                        });
        workers.shutdownNow();
        senders.shutdownNow();
        flushers.shutdownNow();
    }
}
