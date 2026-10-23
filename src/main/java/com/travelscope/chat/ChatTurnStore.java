package com.travelscope.chat;

import com.alibaba.fastjson2.JSON;
import com.travelscope.dto.ChatRequest;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.*;
import java.util.function.Supplier;

/** PostgreSQL is the authority for admission, charging, ordering and finalization. */
@Repository
public class ChatTurnStore {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private String defaultModel = "unknown";

    @org.springframework.beans.factory.annotation.Autowired
    public ChatTurnStore(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            com.travelscope.config.AppProperties properties) {
        this(db, manager);
        this.defaultModel = properties.getModelGateway().getModel();
    }

    public ChatTurnStore(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
    }

    private <T> T transaction(Supplier<T> action) {
        return tx.execute(s -> action.get());
    }

    public Optional<ChatTurn> find(long user, UUID id) {
        return db
                .queryForList("SELECT * FROM chat_turns WHERE user_id=? AND turn_id=?", user, id)
                .stream()
                .findFirst()
                .map(this::turn);
    }

    public ChatTurn owned(long user, UUID id) {
        return find(user, id)
                .orElseThrow(
                        () -> new TurnException(HttpStatus.NOT_FOUND, "TURN_NOT_FOUND", "任务不存在"));
    }

    public List<ChatTurn> list(long user, long conversation, boolean activeOnly) {
        requireConversation(user, conversation);
        return db
                .queryForList(
                        "SELECT * FROM chat_turns WHERE user_id=? AND conversation_id=?"
                                + (activeOnly
                                        ? " AND status IN"
                                                + " ('PREPARING','QUEUED','RUNNING','CANCELLING')"
                                        : "")
                                + " ORDER BY created_at,turn_id",
                        user,
                        conversation)
                .stream()
                .map(this::turn)
                .toList();
    }

    public void requireConversation(long user, long conversation) {
        if (db.queryForObject(
                        "SELECT count(*) FROM conversations WHERE id=? AND user_id=?",
                        Long.class,
                        conversation,
                        user)
                != 1)
            throw new TurnException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "会话不存在");
    }

    public ChatTurn reserve(
            long user, ChatRequest request, String hash, String question, String mediaType) {
        return transaction(
                () -> {
                    // Serializes same request even before a conversation has been assigned.
                    db.queryForList(
                            "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                            user + ":" + request.getTurnId());
                    var existing = find(user, request.getTurnId());
                    if (existing.isPresent()) {
                        if (!existing.get().requestHash().equals(hash) || (existing.get().requestHashVersion() == 1 && !"zh-CN".equals(com.travelscope.common.ReplyLanguage.normalize(request.getLocale()))))
                            throw new TurnException(
                                    HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "同一轮次不能更改消息或图片");
                        return existing.get();
                    }
                    Long conversation = request.getConversationId();
                    if (conversation == null)
                        conversation =
                                db.queryForObject(
                                        "INSERT INTO conversations(user_id) VALUES (?) RETURNING"
                                                + " id",
                                        Long.class,
                                        user);
                    requireConversation(user, conversation);
                    db.queryForList(
                            "SELECT id FROM conversations WHERE id=? FOR UPDATE", conversation);
                    var busy =
                            db.queryForList(
                                    "SELECT turn_id FROM chat_turns WHERE conversation_id=? AND"
                                            + " status IN"
                                            + " ('PREPARING','QUEUED','RUNNING','CANCELLING')",
                                    conversation);
                    if (!busy.isEmpty())
                        throw new TurnException(
                                HttpStatus.CONFLICT,
                                "CONVERSATION_BUSY",
                                "当前会话正在处理，请完成或取消后再发送",
                                (UUID) busy.getFirst().get("turn_id"));
                    UUID source = request.getSourceTurnId();
                    if (isForm(question)) {
                        if (source == null)
                            throw new TurnException(
                                    HttpStatus.CONFLICT, "FORM_STALE", "请重新打开当前需求表单");
                        ChatTurn prior = owned(user, source);
                        UUID latest =
                                db.queryForObject(
                                        "SELECT turn_id FROM chat_turns WHERE user_id=? AND"
                                            + " conversation_id=? ORDER BY created_at DESC,turn_id"
                                            + " DESC LIMIT 1",
                                        UUID.class,
                                        user,
                                        conversation);
                        if (prior.conversationId() != conversation
                                || !source.equals(latest)
                                || !"WAITING_INPUT".equals(prior.outcome())
                                || !prior.snapshot().containsKey("requirementForm")
                                || Boolean.TRUE.equals(prior.snapshot().get("formSubmitted")))
                            throw new TurnException(
                                    HttpStatus.CONFLICT, "FORM_STALE", "该需求表单已处理或已过期");
                    } else if (source != null)
                        throw new TurnException(
                                HttpStatus.BAD_REQUEST, "INVALID_FORM_SOURCE", "只有表单消息可以指定来源轮次");
                    String key =
                            mediaType == null
                                    ? null
                                    : "chat-images/turns/"
                                            + UUID.randomUUID().toString().replace("-", "")
                                            + (mediaType.contains("png")
                                                    ? ".png"
                                                    : mediaType.contains("webp")
                                                            ? ".webp"
                                                            : ".jpg");
                    db.update(
                            "INSERT INTO"
                                + " chat_turns(user_id,turn_id,conversation_id,request_hash,input_text,source_turn_id,image_key,image_type,status,locale,response_locale,request_hash_version)"
                                + " VALUES (?,?,?,?,?,?,?,?,'PREPARING',?,?,2) ON"
                                + " CONFLICT(user_id,turn_id) DO NOTHING",
                            user,
                            request.getTurnId(),
                            conversation,
                            hash,
                            question,
                            source,
                            key,
                            mediaType,
                            com.travelscope.common.ReplyLanguage.normalize(request.getLocale()),
                            com.travelscope.common.ReplyLanguage.resolve(request.getLocale(), question));
                    return owned(user, request.getTurnId());
                });
    }

    public ChatTurn prepare(long user, UUID id) {
        return transaction(
                () -> {
                    ChatTurn t = locked(user, id);
                    if (!"PREPARING".equals(t.status())) return t;
                    String blocks = null;
                    if (t.imageKey() != null)
                        blocks =
                                JSON.toJSONString(
                                        Map.of(
                                                "images",
                                                List.of(
                                                        Map.of(
                                                                "objectKey",
                                                                t.imageKey(),
                                                                "mediaType",
                                                                t.imageType()))));
                    String content =
                            isForm(t.inputText())
                                    ? formLabel(t.inputText())
                                    : (t.imageKey() == null
                                            ? t.inputText()
                                            : "[图片] " + t.inputText());
                    Long message =
                            db.queryForObject(
                                    "INSERT INTO"
                                        + " messages(conversation_id,user_id,role,content,content_blocks,turn_id,token_count,model_name)"
                                        + " VALUES (?,?,'user',?,?::jsonb,?,?,?) RETURNING id",
                                    Long.class,
                                    t.conversationId(),
                                    user,
                                    content,
                                    blocks,
                                    id,
                                    estimateTokens(content),
                                    defaultModel);
                    db.update(
                            "UPDATE conversations SET title=CASE WHEN title='新会话' THEN ? ELSE title"
                                    + " END,updated_at=now() WHERE id=?",
                            content.substring(0, Math.min(30, content.length())),
                            t.conversationId());
                    if (t.sourceTurnId() != null)
                        db.update(
                                "UPDATE chat_turns SET form_consumed_by=? WHERE user_id=? AND"
                                        + " turn_id=?",
                                id,
                                user,
                                t.sourceTurnId());
                    db.update(
                            "UPDATE chat_turns SET"
                                    + " status='QUEUED',user_message_id=?,updated_at=now() WHERE"
                                    + " user_id=? AND turn_id=?",
                            message,
                            user,
                            id);
                    return owned(user, id);
                });
    }

    public boolean claim(long user, UUID id) {
        return db.update(
                        "UPDATE chat_turns SET status='RUNNING',updated_at=now() WHERE user_id=?"
                                + " AND turn_id=? AND status='QUEUED'",
                        user,
                        id)
                == 1;
    }

    public boolean charge(long user, UUID id, int amount) {
        return transaction(
                () -> {
                    ChatTurn t = locked(user, id);
                    if (!"RUNNING".equals(t.status())) return false;
                    var row =
                            db.queryForMap(
                                    "SELECT charged_at FROM chat_turns WHERE user_id=? AND"
                                            + " turn_id=?",
                                    user,
                                    id);
                    if (row.get("charged_at") != null) return true;
                    if (db.update(
                                    "UPDATE users SET points_balance=points_balance-? WHERE id=?"
                                            + " AND points_balance>=?",
                                    amount,
                                    user,
                                    amount)
                            != 1) return false;
                    db.update(
                            "UPDATE chat_turns SET charged_at=now(),charged_points=? WHERE"
                                    + " user_id=? AND turn_id=?",
                            amount,
                            user,
                            id);
                    return true;
                });
    }

    public long append(long user, UUID id, String type, Object payload) {
        return transaction(
                () -> {
                    ChatTurn t = locked(user, id);
                    if (!"RUNNING".equals(t.status())) return 0L;
                    var snapshot = t.snapshot();
                    TurnSnapshot.apply(snapshot, type, payload);
                    return writeEvent(t, type, payload, snapshot);
                });
    }

    private long writeEvent(ChatTurn t, String type, Object payload, Map<String, Object> snapshot) {
        long seq = t.lastSeq() + 1;
        db.update(
                "INSERT INTO chat_turn_events(user_id,turn_id,seq,event_type,payload) VALUES"
                        + " (?,?,?,?,?::jsonb)",
                t.userId(),
                t.turnId(),
                seq,
                type,
                JSON.toJSONString(
                        payload, com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue));
        db.update(
                "UPDATE chat_turns SET"
                        + " last_seq=?,snapshot_seq=?,snapshot_json=?::jsonb,updated_at=now() WHERE"
                        + " user_id=? AND turn_id=?",
                seq,
                seq,
                JSON.toJSONString(
                        snapshot, com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue),
                t.userId(),
                t.turnId());
        return seq;
    }

    public ChatTurn finish(
            long user,
            UUID id,
            String desiredStatus,
            String outcome,
            String content,
            String errorCode,
            String errorMessage,
            Integer tokens,
            String model) {
        return transaction(
                () -> {
                    ChatTurn t = locked(user, id);
                    if (!t.active()) return t;
                    String status =
                            "CANCELLING".equals(t.status()) && !"INTERRUPTED".equals(desiredStatus)
                                    ? "CANCELLED"
                                    : desiredStatus;
                    String finalOutcome = "SUCCEEDED".equals(status) ? outcome : status;
                    String finalCode = "CANCELLED".equals(status) ? "CANCELLED" : errorCode;
                    String finalMessage = "CANCELLED".equals(status) ? "已停止本轮任务" : errorMessage;
                    String body =
                            "SUCCEEDED".equals(status)
                                    ? Objects.toString(content, "")
                                    : Objects.toString(t.snapshot().get("content"), "");
                    Long message = t.assistantMessageId();
                    if (t.userMessageId() != null) {
                        message =
                                db.queryForObject(
                                        "INSERT INTO"
                                            + " messages(conversation_id,user_id,role,content,turn_id,token_count,model_name)"
                                            + " VALUES (?,?,'assistant',?,?,?,?) ON"
                                            + " CONFLICT(user_id,turn_id,role) WHERE turn_id IS NOT"
                                            + " NULL AND role IN ('user','assistant') DO UPDATE SET"
                                            + " content=EXCLUDED.content,token_count=EXCLUDED.token_count,model_name=EXCLUDED.model_name"
                                            + " RETURNING id",
                                        Long.class,
                                        t.conversationId(),
                                        user,
                                        body,
                                        id,
                                        tokens == null ? estimateTokens(body) + 120 : tokens,
                                        model == null ? defaultModel : model);
                    }
                    var terminal = new LinkedHashMap<String, Object>();
                    terminal.put("status", status);
                    terminal.put("outcome", finalOutcome);
                    terminal.put("content", body);
                    terminal.put("assistantMessageId", message);
                    terminal.put(
                            "error",
                            finalCode == null
                                    ? null
                                    : Map.of(
                                            "code",
                                            finalCode,
                                            "message",
                                            Objects.toString(finalMessage, "处理未完成")));
                    var snapshot = t.snapshot();
                    TurnSnapshot.apply(snapshot, "terminal", terminal);
                    writeEvent(t, "terminal", terminal, snapshot);
                    db.update(
                            "UPDATE chat_turns SET"
                                + " status=?,outcome=?,assistant_message_id=?,error_code=?,error_message=?,finished_at=now(),updated_at=now()"
                                + " WHERE user_id=? AND turn_id=?",
                            status,
                            finalOutcome,
                            message,
                            finalCode,
                            finalMessage,
                            user,
                            id);
                    db.update(
                            "UPDATE conversations SET updated_at=now() WHERE id=?",
                            t.conversationId());
                    return owned(user, id);
                });
    }

    public ChatTurn requestCancel(long user, UUID id) {
        return transaction(
                () -> {
                    ChatTurn t = locked(user, id);
                    if (t.active() && !"CANCELLING".equals(t.status()))
                        db.update(
                                "UPDATE chat_turns SET status='CANCELLING',updated_at=now() WHERE"
                                        + " user_id=? AND turn_id=?",
                                user,
                                id);
                    return owned(user, id);
                });
    }

    public void validateCursor(ChatTurn t, long after) {
        if (after < 0) throw new TurnException(HttpStatus.BAD_REQUEST, "CURSOR_INVALID", "事件序号不合法");
        if (after > t.lastSeq())
            throw new TurnException(HttpStatus.CONFLICT, "CURSOR_INVALID", "请重新同步当前任务");
        if (t.eventsExpired() && after < t.lastSeq())
            throw new TurnException(HttpStatus.GONE, "REPLAY_EXPIRED", "逐条事件已过期，请恢复完整快照");
    }

    public List<Map<String, Object>> events(long user, UUID id, long after) {
        return db
                .queryForList(
                        "SELECT seq,event_type,payload FROM chat_turn_events WHERE user_id=? AND"
                                + " turn_id=? AND seq>? ORDER BY seq LIMIT 200",
                        user,
                        id,
                        after)
                .stream()
                .map(
                        row -> {
                            var e = new LinkedHashMap<String, Object>();
                            e.put("turnId", id);
                            e.put("seq", ((Number) row.get("seq")).longValue());
                            e.put("type", row.get("event_type"));
                            e.put("payload", JSON.parse(row.get("payload").toString()));
                            return (Map<String, Object>) e;
                        })
                .toList();
    }

    public List<ChatTurn> active() {
        return db
                .queryForList(
                        "SELECT * FROM chat_turns WHERE status IN"
                            + " ('PREPARING','QUEUED','RUNNING','CANCELLING') ORDER BY created_at")
                .stream()
                .map(this::turn)
                .toList();
    }

    public void expire(int hours) {
        transaction(
                () -> {
                    db.update(
                            "UPDATE chat_turns SET events_expired=true WHERE finished_at<now()-(? *"
                                    + " interval '1 hour') AND NOT events_expired",
                            hours);
                    db.update(
                            "DELETE FROM chat_turn_events e USING chat_turns t WHERE"
                                    + " e.user_id=t.user_id AND e.turn_id=t.turn_id AND"
                                    + " t.events_expired");
                    return null;
                });
    }

    public List<ChatTurn> orphanImages() {
        return db
                .queryForList(
                        "SELECT * FROM chat_turns WHERE finished_at<now()-interval '24 hours' AND"
                                + " user_message_id IS NULL AND image_key IS NOT NULL AND NOT"
                                + " image_cleaned")
                .stream()
                .map(this::turn)
                .toList();
    }

    public void imageCleaned(ChatTurn t) {
        db.update(
                "UPDATE chat_turns SET image_cleaned=true WHERE user_id=? AND turn_id=? AND"
                        + " user_message_id IS NULL",
                t.userId(),
                t.turnId());
    }

    private ChatTurn locked(long user, UUID id) {
        db.queryForList(
                "SELECT turn_id FROM chat_turns WHERE user_id=? AND turn_id=? FOR UPDATE",
                user,
                id);
        return owned(user, id);
    }

    private ChatTurn turn(Map<String, Object> r) {
        @SuppressWarnings("unchecked")
        var snapshot = (Map<String, Object>) JSON.parse(r.get("snapshot_json").toString());
        if (r.get("form_consumed_by") != null) snapshot.put("formSubmitted", true);
        return new ChatTurn(
                ((Number) r.get("user_id")).longValue(),
                (UUID) r.get("turn_id"),
                ((Number) r.get("conversation_id")).longValue(),
                (String) r.get("request_hash"),
                Objects.toString(r.get("locale"), "zh-CN"),
                Objects.toString(r.get("response_locale"), "zh-CN"),
                r.get("request_hash_version") instanceof Number v ? v.intValue() : 1,
                (String) r.get("input_text"),
                (UUID) r.get("source_turn_id"),
                (String) r.get("image_key"),
                (String) r.get("image_type"),
                (String) r.get("status"),
                number(r.get("user_message_id")),
                number(r.get("assistant_message_id")),
                ((Number) r.get("last_seq")).longValue(),
                ((Number) r.get("snapshot_seq")).longValue(),
                snapshot,
                (String) r.get("outcome"),
                (String) r.get("error_code"),
                (String) r.get("error_message"),
                (boolean) r.get("events_expired"),
                ((Timestamp) r.get("created_at")).toInstant(),
                ((Timestamp) r.get("updated_at")).toInstant());
    }

    private Long number(Object n) {
        return n == null ? null : ((Number) n).longValue();
    }

    private static int estimateTokens(String text) {
        return text == null || text.isBlank() ? 0 : (int) Math.ceil(text.length() / 1.5);
    }

    public static boolean isForm(String text) {
        try {
            return JSON.parseObject(text).getJSONObject("__form__") != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static String formLabel(String text) {
        String action = JSON.parseObject(text).getJSONObject("__form__").getString("__action__");
        return "cancel".equals(action) ? "（取消规划）" : "skip".equals(action) ? "（按推荐安排）" : "（提交需求清单）";
    }
}
