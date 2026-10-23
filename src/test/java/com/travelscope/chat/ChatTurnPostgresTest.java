package com.travelscope.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.travelscope.dto.ChatEvent;
import com.travelscope.dto.ChatRequest;
import com.travelscope.entity.Conversation;
import com.travelscope.service.ChatService;
import com.travelscope.service.ConversationService;
import com.travelscope.service.MinioService;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Real PostgreSQL constraints and races; never connects to a business database. */
class ChatTurnPostgresTest {
    @Test
    void chargeBalanceAndMarkerRollBackTogether() {
        var t = started();
        db.execute(
                "CREATE FUNCTION fail_charge() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF"
                    + " NEW.charged_at IS NOT NULL AND OLD.charged_at IS NULL THEN RAISE EXCEPTION"
                    + " 'test charge marker unavailable'; END IF; RETURN NEW; END $$");
        db.execute(
                "CREATE TRIGGER fail_charge BEFORE UPDATE ON chat_turns FOR EACH ROW EXECUTE"
                    + " FUNCTION fail_charge()");
        assertThatThrownBy(() -> store.charge(1, t.turnId(), 50)).isInstanceOf(Exception.class);
        assertThat(db.queryForObject("SELECT points_balance FROM users WHERE id=1", Long.class))
                .isEqualTo(1000);
        assertThat(db.queryForMap("SELECT charged_at FROM chat_turns").get("charged_at")).isNull();
        db.execute("DROP TRIGGER fail_charge ON chat_turns");
        assertThat(store.charge(1, t.turnId(), 50)).isTrue();
        assertThat(db.queryForObject("SELECT points_balance FROM users WHERE id=1", Long.class))
                .isEqualTo(950);
    }

    JdbcTemplate db, admin;
    ChatTurnStore store;
    String schema;
    ChatTurnService service;
    MinioService fakeImages;

    @BeforeEach
    void database() throws Exception {
        String url = System.getenv("RECONNECT_TEST_DATABASE_URL");
        Assumptions.assumeTrue(
                url != null, "Set RECONNECT_TEST_DATABASE_URL to an isolated database");
        if (!url.matches("jdbc:postgresql://[^/]+/travelscope_reconnect_test(?:\\?.*)?"))
            throw new IllegalArgumentException("Only travelscope_reconnect_test is allowed");
        var ds =
                new DriverManagerDataSource(
                        url,
                        System.getenv().getOrDefault("RECONNECT_TEST_DATABASE_USER", "root"),
                        System.getenv().getOrDefault("RECONNECT_TEST_DATABASE_PASSWORD", "root"));
        admin = new JdbcTemplate(ds);
        schema = "reconnect_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var isolated =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        ds.getUsername(),
                        ds.getPassword());
        db = new JdbcTemplate(isolated);
        db.execute(
                "CREATE TABLE users(id BIGSERIAL PRIMARY KEY,points_balance BIGINT NOT NULL DEFAULT"
                        + " 1000)");
        db.execute(
                "CREATE TABLE conversations(id BIGSERIAL PRIMARY KEY,user_id BIGINT REFERENCES"
                    + " users(id),title TEXT NOT NULL DEFAULT '新会话',updated_at TIMESTAMP DEFAULT"
                    + " now())");
        db.execute(
                "CREATE TABLE messages(id BIGSERIAL PRIMARY KEY,conversation_id BIGINT REFERENCES"
                        + " conversations(id),user_id BIGINT REFERENCES users(id),role TEXT NOT"
                        + " NULL,content TEXT NOT NULL,content_blocks JSONB,token_count"
                        + " INTEGER,model_name TEXT,created_at TIMESTAMP DEFAULT now())");
        try (var c = isolated.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("chat-reconnect-upgrade.sql"));
            ScriptUtils.executeSqlScript(c, new ClassPathResource("chat-reconnect-upgrade.sql"));
        }
        db.update("INSERT INTO users(id) VALUES (1),(2)");
        store = new ChatTurnStore(db, new DataSourceTransactionManager(isolated));
    }

    @AfterEach
    void cleanup() {
        if (service != null) service.close();
        if (admin != null && schema != null && schema.matches("reconnect_[0-9a-f]{32}"))
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    ChatRequest request(Long conversation) {
        ChatRequest r = new ChatRequest();
        r.setTurnId(UUID.randomUUID());
        r.setConversationId(conversation);
        r.setMessage("杭州三日游");
        return r;
    }

    ChatTurn reserved(ChatRequest r) {
        return store.reserve(1, r, "hash", "杭州三日游", null);
    }

    ChatTurn started() {
        var t = reserved(request(null));
        store.prepare(1, t.turnId());
        assertThat(store.claim(1, t.turnId())).isTrue();
        return store.owned(1, t.turnId());
    }

    ChatTurn finish(ChatTurn t, String status) {
        return store.finish(1, t.turnId(), status, "REPLIED", "完整正文", null, null, 42, "fake");
    }

    long count(String table) {
        return db.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    @Test
    void concurrentDuplicateAdmissionCreatesOneConversationMessageAndCharge() throws Exception {
        var r = request(null);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<ChatTurn>>();
            for (int i = 0; i < 20; i++)
                tasks.add(
                        pool.submit(
                                () -> {
                                    var t = reserved(r);
                                    return store.prepare(1, t.turnId());
                                }));
            var first = tasks.getFirst().get(10, TimeUnit.SECONDS);
            for (var f : tasks)
                assertThat(f.get(10, TimeUnit.SECONDS).conversationId())
                        .isEqualTo(first.conversationId());
            assertThat(count("chat_turns")).isEqualTo(1);
            assertThat(count("conversations")).isEqualTo(1);
            assertThat(count("messages")).isEqualTo(1);
            assertThat(store.claim(1, r.getTurnId())).isTrue();
            assertThat(store.claim(1, r.getTurnId())).isFalse();
            var charges = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 20; i++)
                charges.add(pool.submit(() -> store.charge(1, r.getTurnId(), 50)));
            for (var f : charges) assertThat(f.get()).isTrue();
            assertThat(db.queryForObject("SELECT points_balance FROM users WHERE id=1", Long.class))
                    .isEqualTo(950);
        }
    }

    @Test
    void hashConflictAndConversationBusyDoNotCreateExtraTurns() {
        var r = request(null);
        var t = reserved(r);
        assertThatThrownBy(() -> store.reserve(1, r, "changed", "换一张图片", null))
                .isInstanceOfSatisfying(
                        TurnException.class,
                        e -> assertThat(e.code).isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThatThrownBy(() -> reserved(request(t.conversationId())))
                .isInstanceOfSatisfying(
                        TurnException.class,
                        e -> assertThat(e.code).isEqualTo("CONVERSATION_BUSY"));
        assertThat(count("chat_turns")).isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "INSERT INTO"
                                            + " chat_turns(user_id,turn_id,conversation_id,request_hash,input_text,status)"
                                            + " VALUES (1,?,?, 'x','x','QUEUED')",
                                        UUID.randomUUID(),
                                        t.conversationId()))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void cancellationAndSuccessHaveExactlyOneTerminalAndRejectLateOutput() throws Exception {
        var t = started();
        store.append(1, t.turnId(), "delta", "已保存");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a =
                    pool.submit(
                            () -> {
                                store.requestCancel(1, t.turnId());
                                return finish(t, "CANCELLED");
                            });
            var b = pool.submit(() -> finish(t, "SUCCEEDED"));
            a.get();
            b.get();
        }
        ChatTurn end = store.owned(1, t.turnId());
        assertThat(end.active()).isFalse();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM chat_turn_events WHERE event_type='terminal'",
                                Long.class))
                .isEqualTo(1);
        assertThat(count("messages")).isEqualTo(2);
        assertThat(store.append(1, t.turnId(), "delta", "迟到输出")).isZero();
        assertThat(store.charge(1, t.turnId(), 50)).isFalse();
        assertThat(end.outcome())
                .isEqualTo(end.status().equals("SUCCEEDED") ? "REPLIED" : "CANCELLED");
    }

    @Test
    void eventAndTerminalFailuresRollBackMessagesWatermarkAndSnapshot() {
        var t = started();
        db.execute(
                "CREATE FUNCTION fail_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE"
                        + " EXCEPTION 'test storage unavailable'; END $$");
        db.execute(
                "CREATE TRIGGER fail_event BEFORE INSERT ON chat_turn_events FOR EACH ROW EXECUTE"
                        + " FUNCTION fail_event()");
        assertThatThrownBy(() -> store.append(1, t.turnId(), "delta", "不可见"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> finish(t, "SUCCEEDED")).isInstanceOf(Exception.class);
        assertThat(count("messages")).isEqualTo(1);
        assertThat(count("chat_turn_events")).isZero();
        assertThat(store.owned(1, t.turnId()).lastSeq()).isZero();
        assertThat(store.owned(1, t.turnId()).status()).isEqualTo("RUNNING");
        db.execute("DROP TRIGGER fail_event ON chat_turn_events");
        assertThat(finish(t, "SUCCEEDED").snapshot().get("content")).isEqualTo("完整正文");
    }

    @Test
    void replayRemainsOrderedWhileWriterAppendsAndExpiryKeepsSnapshotAndDedupe() throws Exception {
        var t = started();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer =
                    pool.submit(
                            () -> {
                                for (int i = 1; i <= 40; i++)
                                    store.append(1, t.turnId(), "delta", "字");
                                finish(t, "SUCCEEDED");
                            });
            long cursor = 0;
            var received = new ArrayList<Long>();
            while (!writer.isDone() || cursor < store.owned(1, t.turnId()).lastSeq()) {
                for (var e : store.events(1, t.turnId(), cursor)) {
                    long seq = ((Number) e.get("seq")).longValue();
                    assertThat(seq).isEqualTo(cursor + 1);
                    cursor = seq;
                    received.add(seq);
                }
                Thread.sleep(5);
            }
            writer.get();
            assertThat(received).hasSize(41);
        }
        db.update("UPDATE chat_turns SET finished_at=now()-interval '25 hours'");
        store.expire(24);
        var saved = store.owned(1, t.turnId());
        assertThat(saved.snapshot().get("content")).isEqualTo("完整正文");
        assertThat(count("messages")).isEqualTo(2);
        assertThat(count("chat_turn_events")).isZero();
        assertThatThrownBy(() -> store.validateCursor(saved, 0))
                .isInstanceOfSatisfying(
                        TurnException.class, e -> assertThat(e.code).isEqualTo("REPLAY_EXPIRED"));
        assertThatThrownBy(() -> store.validateCursor(saved, 100))
                .isInstanceOfSatisfying(
                        TurnException.class, e -> assertThat(e.code).isEqualTo("CURSOR_INVALID"));
        assertThat(store.find(1, t.turnId())).isPresent();
    }

    @Test
    void formConsumptionSurvivesReplayAndStaleCardCannotStartAnotherTurn() {
        var t = started();
        store.append(1, t.turnId(), "requirement_form", Map.of("fields", List.of()));
        store.finish(1, t.turnId(), "SUCCEEDED", "WAITING_INPUT", "请确认", null, null, 0, null);
        var r = request(t.conversationId());
        r.setSourceTurnId(t.turnId());
        r.setMessage("{\"__form__\":{\"__action__\":\"submit\"}}");
        var next = store.reserve(1, r, "formhash", r.getMessage(), null);
        store.prepare(1, next.turnId());
        finish(next, "SUCCEEDED");
        assertThat(store.owned(1, t.turnId()).snapshot().get("formSubmitted")).isEqualTo(true);
        var again = request(t.conversationId());
        again.setSourceTurnId(t.turnId());
        again.setMessage(r.getMessage());
        assertThatThrownBy(() -> store.reserve(1, again, "new", again.getMessage(), null))
                .isInstanceOfSatisfying(
                        TurnException.class, e -> assertThat(e.code).isEqualTo("FORM_STALE"));
    }

    @Test
    void ownershipIsEnforcedForAllReadsAndReservations() {
        var t = started();
        assertThatThrownBy(() -> store.owned(2, t.turnId()))
                .isInstanceOfSatisfying(
                        TurnException.class, e -> assertThat(e.status.value()).isEqualTo(404));
        assertThatThrownBy(() -> store.list(2, t.conversationId(), false))
                .isInstanceOf(TurnException.class);
        var r = request(t.conversationId());
        assertThatThrownBy(() -> store.reserve(2, r, "h", "x", null))
                .isInstanceOf(TurnException.class);
    }

    ChatService runner(Flux<ChatEvent> flow, AtomicInteger executions) {
        return runner(flow, executions, 900);
    }

    ChatService runner(Flux<ChatEvent> flow, AtomicInteger executions, int totalSeconds) {
        ChatService chat = mock(ChatService.class);
        var conversations = mock(ConversationService.class);
        var images = mock(MinioService.class);
        fakeImages = images;
        when(conversations.getOwnedConversation(anyLong(), anyLong()))
                .thenAnswer(
                        inv -> {
                            var c = new Conversation();
                            c.setId(inv.getArgument(0));
                            c.setUserId(inv.getArgument(1));
                            return c;
                        });
        when(chat.execution(any(), anyLong(), anyString(), nullable(String.class), any(), anyString(), any()))
                .thenAnswer(
                        inv -> {
                            java.util.function.IntPredicate charge = inv.getArgument(6);
                            var execution = new ChatService.TurnExecution();
                            execution.events =
                                    Flux.defer(
                                            () -> {
                                                executions.incrementAndGet();
                                                assertThat(charge.test(50)).isTrue();
                                                return flow;
                                            });
                            return execution;
                        });
        when(chat.executionResult(any()))
                .thenReturn(new ChatService.TurnResult("完整正文", "REPLIED", 42, "fake"));
        when(chat.executionError(any())).thenReturn("假模型失败");
        var config = new ReconnectProperties();
        config.setExecutionSeconds(totalSeconds);
        service = new ChatTurnService(store, chat, conversations, images, config);
        service.recover();
        return chat;
    }

    static void until(BooleanSupplier condition) throws Exception {
        long limit = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < limit) Thread.sleep(10);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test
    void acceptedTurnExecutesWithoutSseAndRetryNeverExecutesOrChargesAgain() throws Exception {
        var executions = new AtomicInteger();
        runner(Flux.just(ChatEvent.of("delta", "半截")), executions);
        var r = request(null);
        var first = service.submit(1, r);
        until(() -> !store.owned(1, r.getTurnId()).active());
        var retry = service.submit(1, r);
        assertThat(retry.conversationId()).isEqualTo(first.conversationId());
        assertThat(executions).hasValue(1);
        assertThat(retry.status()).isEqualTo("SUCCEEDED");
        assertThat(count("messages")).isEqualTo(2);
        assertThat(db.queryForObject("SELECT points_balance FROM users WHERE id=1", Long.class))
                .isEqualTo(950);
    }

    @Test
    void explicitCancelPersistsPartialTextAndRestartInterruptsWithoutRerun() throws Exception {
        var sink = Sinks.many().unicast().<ChatEvent>onBackpressureBuffer();
        var executions = new AtomicInteger();
        runner(sink.asFlux(), executions);
        var r = request(null);
        service.submit(1, r);
        until(() -> executions.get() == 1);
        sink.tryEmitNext(ChatEvent.of("delta", "已保存内容"));
        until(() -> store.owned(1, r.getTurnId()).lastSeq() > 0);
        service.cancel(1, r.getTurnId());
        until(() -> !store.owned(1, r.getTurnId()).active());
        assertThat(store.owned(1, r.getTurnId()).status()).isEqualTo("CANCELLED");
        assertThat(store.owned(1, r.getTurnId()).snapshot().get("content")).isEqualTo("已保存内容");
        var orphan = started();
        store.append(1, orphan.turnId(), "delta", "重启前");
        store.requestCancel(1, orphan.turnId());
        service.recover();
        var restored = store.owned(1, orphan.turnId());
        assertThat(restored.status()).isEqualTo("INTERRUPTED");
        assertThat(restored.snapshot().get("content")).isEqualTo("重启前");
        assertThat(executions).hasValue(1);
    }

    @Test
    void imageRetryUsesSameObjectReferenceAndNeverStoresBase64() {
        var r = request(null);
        var t = store.reserve(1, r, "imagehash", "这是哪里", "image/jpeg");
        var retry = store.reserve(1, r, "imagehash", "这是哪里", "image/jpeg");
        assertThat(retry.imageKey()).isEqualTo(t.imageKey());
        store.prepare(1, t.turnId());
        store.prepare(1, t.turnId());
        assertThat(count("messages")).isEqualTo(1);
        String blocks =
                db.queryForObject("SELECT content_blocks::text FROM messages", String.class);
        assertThat(blocks).contains(t.imageKey()).doesNotContain("base64");
        assertThat(db.queryForObject("SELECT content FROM messages", String.class))
                .isEqualTo("[图片] 这是哪里");
    }

    @Test
    void latePreparationCannotReactivateTerminalTurn() {
        var t = reserved(request(null));
        store.requestCancel(1, t.turnId());
        finish(t, "CANCELLED");
        assertThat(store.prepare(1, t.turnId()).status()).isEqualTo("CANCELLED");
        assertThat(count("messages")).isZero();
    }

    @Test
    void failedImageUploadRetriesOriginalObjectAndExecutesOnce() throws Exception {
        var executions = new AtomicInteger();
        runner(Flux.just(ChatEvent.of("delta", "图片讲解")), executions);
        byte[] image = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 0, 0, 0};
        doThrow(new IllegalStateException("test upload unavailable"))
                .doNothing()
                .when(fakeImages)
                .upload(anyString(), any(), anyLong(), anyString());
        when(fakeImages.download(anyString())).thenAnswer(inv -> new ByteArrayInputStream(image));
        var r = request(null);
        r.setImageBase64(Base64.getEncoder().encodeToString(image));
        assertThatThrownBy(() -> service.submit(1, r)).isInstanceOf(TurnException.class);
        String key = store.owned(1, r.getTurnId()).imageKey();
        service.submit(1, r);
        until(() -> !store.owned(1, r.getTurnId()).active());
        service.submit(1, r);
        verify(fakeImages, times(2))
                .upload(eq(key), any(), eq((long) image.length), eq("image/jpeg"));
        assertThat(executions).hasValue(1);
        assertThat(count("messages")).isEqualTo(2);
        assertThat(db.queryForObject("SELECT input_text FROM chat_turns", String.class))
                .doesNotContain(r.getImageBase64());
    }

    @Test
    void runningPersistenceFailureStopsGenerationAndDoesNotPublishUncommittedEvents()
            throws Exception {
        var sink = Sinks.many().unicast().<ChatEvent>onBackpressureBuffer();
        var executions = new AtomicInteger();
        runner(sink.asFlux(), executions);
        var r = request(null);
        service.submit(1, r);
        until(() -> executions.get() == 1);
        db.execute(
                "CREATE FUNCTION fail_append() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF"
                        + " NEW.event_type <> 'terminal' THEN RAISE EXCEPTION 'test append"
                        + " unavailable'; END IF; RETURN NEW; END $$");
        db.execute(
                "CREATE TRIGGER fail_append BEFORE INSERT ON chat_turn_events FOR EACH ROW EXECUTE"
                        + " FUNCTION fail_append()");
        sink.tryEmitNext(
                ChatEvent.of(
                        "agent_status",
                        "{\"agent\":\"test\",\"action\":\"不可见\",\"state\":\"RUNNING\"}"));
        until(() -> !store.owned(1, r.getTurnId()).active());
        var end = store.owned(1, r.getTurnId());
        assertThat(end.status()).isEqualTo("FAILED");
        assertThat(store.events(1, r.getTurnId(), 0))
                .hasSize(1)
                .allMatch(e -> e.get("type").equals("terminal"));
        assertThat(end.snapshot().get("content")).isEqualTo("");
        assertThat(executions).hasValue(1);
    }

    @Test
    void totalDeadlineDoesNotResetWhenDeltasKeepArriving() throws Exception {
        var executions = new AtomicInteger();
        runner(
                Flux.interval(Duration.ofMillis(80)).map(i -> ChatEvent.of("delta", "字")),
                executions,
                1);
        var r = request(null);
        service.submit(1, r);
        until(() -> !store.owned(1, r.getTurnId()).active());
        var end = store.owned(1, r.getTurnId());
        assertThat(end.status()).isEqualTo("FAILED");
        assertThat(end.snapshot().get("content").toString()).isNotBlank();
        assertThat(executions).hasValue(1);
    }

    @Test
    void twoSseSubscribersReplaySameJournalWithoutExecutingAgain() throws Exception {
        var executions = new AtomicInteger();
        runner(Flux.just(ChatEvent.of("delta", "片段")), executions);
        var r = request(null);
        service.submit(1, r);
        until(() -> !store.owned(1, r.getTurnId()).active());
        var controller = new com.travelscope.controller.ChatTurnController(service);
        var mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                controller)
                        .setControllerAdvice(new TurnExceptionHandler())
                        .build();
        var user = new com.travelscope.entity.User();
        user.setId(1L);
        for (int i = 0; i < 2; i++) {
            var result =
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.get(
                                                    "/chat/turns/" + r.getTurnId() + "/events")
                                            .requestAttr(
                                                    com.travelscope.security.AuthInterceptor
                                                            .ATTR_CURRENT_USER,
                                                    user))
                            .andReturn();
            result.getAsyncResult(5000);
            var complete =
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.asyncDispatch(result))
                            .andReturn();
            String body =
                    complete.getResponse()
                            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(body).contains("id:1", "event:delta", "event:terminal", "完整正文");
            assertThat(body.indexOf("event:delta")).isLessThan(body.indexOf("event:terminal"));
        }
        assertThat(executions).hasValue(1);
        assertThat(count("messages")).isEqualTo(2);
    }
}
