package com.travelscope.assessment;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The only writer of new call metrics. SDK callbacks copy data and enqueue, never perform I/O. */
@Component
public class AssessmentTraceProcessor implements SpanProcessor {
    static final String VERSION = "OTEL_V1";
    static AttributeKey<String> key(String name) { return AttributeKey.stringKey("travelscope." + name); }
    static String value(Attributes a, String name) { return a.get(key(name)); }
    private final JdbcTemplate jdbc;
    private final AssessmentProperties properties;
    private final ThreadPoolExecutor writer;
    private final Set<UUID> incomplete = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean degraded = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong(), failures = new AtomicLong();
    private final ObjectMapper json = new ObjectMapper();

    public AssessmentTraceProcessor(JdbcTemplate jdbc, AssessmentProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
        writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, properties.getQueueCapacity())),
                r -> { var t = new Thread(r, "assessment-trace-writer"); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public boolean isStartRequired() { return true; }
    public boolean isEndRequired() { return true; }
    public void onStart(Context parent, ReadWriteSpan span) { accept(span.toSpanData(), false); }
    public void onEnd(ReadableSpan span) { accept(span.toSpanData(), true); }
    void checkpoint(io.opentelemetry.api.trace.Span span) {
        if(span instanceof ReadableSpan readable)accept(readable.toSpanData(),false);
    }

    private void accept(SpanData data, boolean ended) {
        String run = value(data.getAttributes(), "run_id");
        if (run == null || !properties.isEnabled()) return;
        try {
            UUID id = UUID.fromString(run);
            write(id, () -> project(data, ended));
        } catch (IllegalArgumentException badMetadata) { degraded.set(true); }
    }

    void write(UUID run, Runnable action) {
        if (!properties.isEnabled()) return;
        try {
            writer.execute(() -> {
                try { action.run(); }
                catch (Exception e) {
                    markIncomplete(run); failures.incrementAndGet();
                    LoggerFactory.getLogger(getClass()).warn("Trace persistence failed: runId={} type={}",
                            run, e.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException full) { markIncomplete(run); dropped.incrementAndGet(); }
    }

    void markIncomplete(UUID run) {
        if (incomplete.size() < Math.max(1, properties.getQueueCapacity())) incomplete.add(run);
        else degraded.set(true);
    }
    boolean complete(UUID run) { return !degraded.get() && !incomplete.contains(run); }
    public Map<String, Object> health() {
        return Map.of("enabled", properties.isEnabled(), "queueDepth", writer.getQueue().size(),
                "droppedWrites", dropped.get(), "persistenceFailures", failures.get(),
                "pendingIncompleteRuns", incomplete.size(),
                "degraded", degraded.get() || dropped.get() > 0 || failures.get() > 0);
    }
    void persistIncomplete() {
        for (UUID id : List.copyOf(incomplete)) {
            try {
                if(jdbc.update("UPDATE assessment_runs SET data_complete=false WHERE id=?", id)>0){
                    var rows=jdbc.queryForList("SELECT status FROM assessment_runs WHERE id=?",id);
                    // Keep gaps for an unfinished root: a delayed start must not erase a real failure.
                    if(!rows.isEmpty()&&!"RUNNING".equals(rows.getFirst().get("status")))incomplete.remove(id);
                }
            }
            catch (Exception e) { break; }
        }
    }

    private void project(SpanData d, boolean ended) {
        Attributes a = d.getAttributes();
        UUID run = uuid(value(a, "run_id"));
        String kind = value(a, "kind");
        if (!Set.of("RUN", "AGENT", "MODEL_CALL", "MODEL", "TOOL").contains(kind)) return;
        if ("RUN".equals(kind)) {
            jdbc.update("""
                INSERT INTO assessment_runs(id,turn_id,trace_id,session_id,sample_id,source,status,
                  started_at,root_span_id,collection_version,trace_attributes,trace_root_seen)
                VALUES (?,?,?,?,?,?,'RUNNING',?,?,?,?::jsonb,true) ON CONFLICT(id) DO UPDATE SET
                  started_at=CASE WHEN assessment_runs.trace_root_seen THEN assessment_runs.started_at ELSE excluded.started_at END,
                  session_id=coalesce(assessment_runs.session_id,excluded.session_id),
                  root_span_id=coalesce(assessment_runs.root_span_id,excluded.root_span_id),
                  data_complete=CASE WHEN assessment_runs.trace_root_seen THEN assessment_runs.data_complete ELSE true END,
                  trace_root_seen=true
                """, run, uuid(value(a,"turn_id")), d.getTraceId(), value(a,"session_id"),
                    uuid(value(a,"sample_id")), value(a,"source"), time(d.getStartEpochNanos()),
                    d.getSpanId(), VERSION, attributes(a));
            jdbc.update("""
                UPDATE assessment_runs SET task_type=coalesce(?,task_type),queue_ms=coalesce(?,queue_ms),trace_attributes=?::jsonb
                WHERE id=? AND status='RUNNING' AND collection_version='OTEL_V1'
                """,value(a,"task_type"),decimal(value(a,"queue_ms")),attributes(a),run);
            if (ended) {
                String status = Objects.requireNonNullElse(value(a,"status"), "INTERRUPTED");
                jdbc.update("""
                    UPDATE assessment_runs SET status=CASE
                      WHEN ?='PLANNING_COMPLETED' AND EXISTS(SELECT 1 FROM assessment_spans WHERE run_id=? AND status='RUNNING' AND phase='PLANNING') THEN 'INCOMPLETE'
                      WHEN ?='PLANNING_COMPLETED' AND NOT EXISTS(SELECT 1 FROM assessment_spans WHERE run_id=? AND kind='AGENT' AND name='planning-agent' AND status='SUCCEEDED') THEN 'INCOMPLETE'
                      ELSE ? END,ended_at=?,duration_ms=?,artifact_available=?,
                      data_complete=data_complete AND ? AND NOT EXISTS(SELECT 1 FROM assessment_spans WHERE run_id=? AND status='RUNNING' AND phase='PLANNING'),
                      planning_artifact_id=?,task_type=?,queue_ms=?,trace_attributes=?::jsonb
                    WHERE id=? AND status='RUNNING' AND collection_version='OTEL_V1'
                    """, status,run,status,run,status,time(d.getEndEpochNanos()),duration(d),
                        Boolean.parseBoolean(value(a,"artifact_available")), complete(run) && !"false".equals(value(a,"data_complete")),
                        run, uuid(value(a,"planning_artifact_id")),value(a,"task_type"),decimal(value(a,"queue_ms")),attributes(a),run);
            }
            return;
        }
        UUID node = uuid(value(a,"node_id"));
        // A child may be replayed before its RUN. Keep it intact for the run's idempotent insert.
        ensureRun(d, a, run);
        jdbc.update("""
            INSERT INTO assessment_spans(id,run_id,parent_id,kind,name,call_id,status,started_at,phase,
              trace_id,otel_span_id,otel_parent_span_id,collection_version,trace_attributes)
            VALUES (?,?,?,?,?,?,'RUNNING',?,?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING
            """, node,run,uuid(value(a,"parent_id")),kind,d.getName(),value(a,"call_id"),time(d.getStartEpochNanos()),
                value(a,"phase"),d.getTraceId(),d.getSpanId(),d.getParentSpanId(),VERSION,attributes(a));
        if (!ended) return;
        String status = Objects.requireNonNullElse(value(a,"status"), "INTERRUPTED");
        // Terminal state and usage are one atomic transition; late duplicate ends cannot replace them.
        jdbc.update("""
            UPDATE assessment_spans SET model=?,input_tokens=?,output_tokens=?,cached_tokens=?,usage_kind=?,
              request_id=?,cost=?,cost_complete=?,price_version=?,status=?,ended_at=?,duration_ms=?,error_type=?,
              trace_attributes=?::jsonb,attempt_group=?,attempt_index=?,ttft_ms=?
            WHERE id=? AND status='RUNNING' AND collection_version='OTEL_V1'
            """,value(a,"model"),integer(value(a,"input_tokens")),integer(value(a,"output_tokens")),integer(value(a,"cached_tokens")),
                Objects.requireNonNullElse(value(a,"usage_kind"),"UNKNOWN"),value(a,"request_id"),decimal(value(a,"cost")),
                Boolean.parseBoolean(value(a,"cost_complete")),value(a,"price_version"),status,time(d.getEndEpochNanos()),
                duration(d),value(a,"error_type"),attributes(a),uuid(value(a,"attempt_group")),
                integer(value(a,"attempt_index")),decimal(value(a,"ttft_ms")),node);
    }

    private void ensureRun(SpanData d, Attributes a, UUID run) {
        jdbc.update("""
            INSERT INTO assessment_runs(id,turn_id,trace_id,session_id,sample_id,source,status,collection_version,data_complete,trace_root_seen)
            VALUES (?,?,?,?,?,?,'RUNNING',?,false,false) ON CONFLICT DO NOTHING
            """,run,uuid(value(a,"turn_id")),d.getTraceId(),value(a,"session_id"),uuid(value(a,"sample_id")),
                Objects.requireNonNullElse(value(a,"source"),"ONLINE"),VERSION);
    }
    private String attributes(Attributes a) {
        Map<String,Object> values = new TreeMap<>();
        a.forEach((k,v) -> { if (k.getKey().startsWith("travelscope.")) values.put(k.getKey(), v); });
        try { return json.writeValueAsString(values); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    static UUID uuid(String s) { return s == null || s.isBlank() ? null : UUID.fromString(s); }
    static Long integer(String s) { return s == null ? null : Long.valueOf(s); }
    static BigDecimal decimal(String s) { return s == null ? null : new BigDecimal(s); }
    static Timestamp time(long ns) { return Timestamp.from(Instant.ofEpochSecond(Math.floorDiv(ns,1_000_000_000L),Math.floorMod(ns,1_000_000_000L))); }
    static double duration(SpanData d) { return Math.max(0, (d.getEndEpochNanos()-d.getStartEpochNanos()) / 1_000_000d); }

    /** FIFO barrier; scoring must never silently use metrics from a failed flush. */
    public void flush() throws Exception {
        boolean pending=!incomplete.isEmpty();
        long failuresBefore=failures.get(),droppedBefore=dropped.get();
        writer.submit(this::persistIncomplete).get(10, TimeUnit.SECONDS);
        if (pending || dropped.get()!=droppedBefore || failures.get()!=failuresBefore || degraded.get())
            throw new IllegalStateException("Trace ingestion is incomplete");
    }
    public CompletableResultCode forceFlush() {
        var result = new CompletableResultCode();
        try { writer.execute(() -> { persistIncomplete(); if(dropped.get()==0 && failures.get()==0 && !degraded.get())result.succeed();else result.fail(); }); }
        catch (RejectedExecutionException e) { result.fail(); }
        return result;
    }
    public CompletableResultCode shutdown() { close(); return CompletableResultCode.ofSuccess(); }
    @PreDestroy public void close() {
        writer.shutdown();
        try { writer.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
