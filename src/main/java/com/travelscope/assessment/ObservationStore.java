package com.travelscope.assessment;

import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;

/** Single bounded FIFO writer: ordering is retained and failures never break the business stream. */
@Service
public class ObservationStore {
    private final JdbcTemplate jdbc;
    private final AssessmentProperties properties;
    private final PayloadRedactor redactor;
    private final AssessmentTraceProcessor processor;
    private final Tracer tracer;
    private final SdkTracerProvider ownedProvider;
    private final Map<UUID, Span> roots = new ConcurrentHashMap<>();
    private final Set<UUID> finishing = ConcurrentHashMap.newKeySet();
    public Map<String,Object> health(){return processor.health();}

    @Autowired
    public ObservationStore(JdbcTemplate jdbc, AssessmentProperties properties, PayloadRedactor redactor,
                            OpenTelemetry telemetry, AssessmentTraceProcessor processor) {
        this.jdbc=jdbc; this.properties=properties; this.redactor=redactor;
        this.processor=processor; this.tracer=telemetry.getTracer("travelscope.assessment", "1");
        this.ownedProvider=null;
    }
    /** Standalone tests still exercise real SDK spans; no exporter or network is installed. */
    public ObservationStore(JdbcTemplate jdbc, AssessmentProperties properties, PayloadRedactor redactor) {
        this.jdbc=jdbc; this.properties=properties; this.redactor=redactor;
        processor=new AssessmentTraceProcessor(jdbc,properties);
        ownedProvider=SdkTracerProvider.builder().setSampler(Sampler.alwaysOn()).addSpanProcessor(processor).build();
        tracer=ownedProvider.get("travelscope.assessment", "1");
    }
    private void markIncomplete(UUID run){processor.markIncomplete(run);}
    private final Map<String,Set<UUID>> activeSessions=new ConcurrentHashMap<>();
    private final Map<UUID,String> runSessions=new ConcurrentHashMap<>();
    private final Set<UUID> ambiguousArtifacts=ConcurrentHashMap.newKeySet();
    private void registerSession(String session,UUID run){
        if(session==null)return;
        if(runSessions.size()>=properties.getQueueCapacity()){processor.markIncomplete(run);return;}
        runSessions.put(run,session);
        activeSessions.compute(session,(key,active)->{
            if(active==null)active=ConcurrentHashMap.newKeySet();active.add(run);
            if(active.size()>1){ambiguousArtifacts.addAll(active);active.forEach(this::markIncomplete);}
            return active;
        });
    }
    public boolean enabled() { return properties.isEnabled(); }
    public ObservationContext begin(String session, UUID sample) { return begin(session,sample,null); }
    public ObservationContext begin(String session,UUID sample,String traceId) {
        return begin(session,sample,traceId,UUID.randomUUID());
    }
    public ObservationContext begin(String session,UUID sample,String traceId,UUID turnId) {
        return begin(session,sample,traceId,turnId,null,null,null);
    }
    public ObservationContext begin(String session,UUID sample,String traceId,UUID turnId,String parentSpanId) {
        return begin(session,sample,traceId,turnId,parentSpanId,null,null);
    }
    public ObservationContext beginAssessment(String session, UUID sample, String mode, UUID batch) {
        return begin(session,sample,null,UUID.randomUUID(),null,mode,batch);
    }
    private ObservationContext begin(String session,UUID sample,String traceId,UUID turnId,
                                     String parentSpanId,String mode,UUID batch) {
        UUID id=UUID.randomUUID();
        var builder=tracer.spanBuilder(sample==null?"chat-turn-execution":"assessment-turn").setNoParent()
            .setAttribute(AssessmentTraceProcessor.key("run_id"),id.toString())
            .setAttribute(AssessmentTraceProcessor.key("kind"),"RUN")
            .setAttribute(AssessmentTraceProcessor.key("turn_id"),turnId.toString())
            .setAttribute(AssessmentTraceProcessor.key("source"),sample==null?"ONLINE":"ASSESSMENT");
        if(session!=null)builder.setAttribute(AssessmentTraceProcessor.key("session_id"),session);
        if(sample!=null)builder.setAttribute(AssessmentTraceProcessor.key("sample_id"),sample.toString());
        if(mode!=null)builder.setAttribute(AssessmentTraceProcessor.key("tool_mode"),mode);
        if(batch!=null)builder.setAttribute(AssessmentTraceProcessor.key("batch_id"),batch.toString());
        if(TraceId.isValid(traceId==null?"":traceId) && SpanId.isValid(parentSpanId==null?"":parentSpanId))
            builder.setParent(Context.root().with(Span.wrap(SpanContext.create(traceId,parentSpanId,TraceFlags.getSampled(),TraceState.getDefault()))));
        Span root=enabled()?builder.startSpan():Span.getInvalid();
        if(enabled())roots.put(id,root);
        ObservationContext ctx=new ObservationContext(id,turnId,root.getSpanContext().getTraceId(),null,sample,"PLANNING",root.getSpanContext());
        if(enabled()&&sample==null)registerSession(session,id);
        if(enabled()){
            if(artifactStates.size()<properties.getQueueCapacity())artifactStates.put(id,new ArtifactState(session));
            else markIncomplete(id);
        }
        return ctx;
    }
    public void write(UUID run,Runnable action){processor.write(run,action);}
    private final Set<UUID> timedOut=ConcurrentHashMap.newKeySet();
    public void timeout(ObservationContext ctx){timedOut.add(ctx.runId());}
    public void finish(ObservationContext ctx, String status, double ignoredLegacyDuration, boolean artifacts) {
        if(!roots.containsKey(ctx.runId())||!finishing.add(ctx.runId()))return;
        final boolean ambiguous=ambiguousArtifacts.remove(ctx.runId());
        ArtifactState artifactsForRun=artifactStates.remove(ctx.runId());
        final ArtifactSnapshot plan=planningSnapshot(artifactsForRun);
        final boolean ownedPlan=plan!=null;
        final UUID finalArtifactId=artifacts&&ownedPlan&&!ambiguous?plan.id():null;
        String session=runSessions.remove(ctx.runId());
        if(session!=null)activeSessions.computeIfPresent(session,(key,active)->{active.remove(ctx.runId());return active.isEmpty()?null:active;});
        final String terminalStatus=timedOut.remove(ctx.runId())?"TIMED_OUT":(ambiguous||!ownedPlan)&&"PLANNING_COMPLETED".equals(status)?"INCOMPLETE":status;
        Span root=roots.remove(ctx.runId());
        if(root==null)return;
        attr(root,"status",terminalStatus);
        attr(root,"artifact_available",artifacts&&ownedPlan&&!ambiguous);
        attr(root,"data_complete",!ambiguous&&processor.complete(ctx.runId()));
        attr(root,"planning_artifact_id",finalArtifactId);
        if(Set.of("FAILED","TIMED_OUT","INTERRUPTED","INCOMPLETE").contains(terminalStatus))root.setStatus(StatusCode.ERROR);
        root.end();
        finishing.remove(ctx.runId());
    }
    public void queue(ObservationContext ctx,double ms){Span root=roots.get(ctx.runId());attr(root,"queue_ms",ms);if(root!=null)processor.checkpoint(root);}
    public void task(ObservationContext ctx,String type){Span root=roots.get(ctx.runId());attr(root,"task_type",type);if(root!=null)processor.checkpoint(root);}
    static void attr(Span span,String key,Object value){if(span!=null&&value!=null)span.setAttribute(AssessmentTraceProcessor.key(key),value.toString());}

    /** Restore only explicit persisted native IDs for a later manual score; never guess by session. */
    ObservationContext attach(ObservationContext ctx) {
        if(ctx.spanContext()!=null&&ctx.spanContext().isValid())return ctx;
        Span running=roots.get(ctx.runId());
        if(running!=null)return ctx.withSpanContext(running.getSpanContext());
        try {
            var rows=jdbc.queryForList("SELECT trace_id,root_span_id FROM assessment_runs WHERE id=?",ctx.runId());
            if(!rows.isEmpty()){
                String trace=Objects.toString(rows.getFirst().get("trace_id"),"");
                String parent=Objects.toString(rows.getFirst().get("root_span_id"),"");
                if(TraceId.isValid(trace)&&SpanId.isValid(parent))
                    return ctx.withSpanContext(SpanContext.create(trace,parent,TraceFlags.getSampled(),TraceState.getDefault()));
            }
        }catch(Exception e){markIncomplete(ctx.runId());}
        return ctx;
    }
    public Handle span(ObservationContext ctx,String kind,String name,String callId,Object input) {
        Handle h=new Handle(attach(ctx),kind,name,callId);
        if("TOOL".equals(kind)){
            boolean local=FixtureTools.local(name);
            java.math.BigDecimal fee=properties.getToolPrices().get(name);
            attr(h.span,"cost",local?java.math.BigDecimal.ZERO:fee);
            attr(h.span,"cost_complete",local||fee!=null);
            attr(h.span,"price_version",properties.getPriceVersion());
        }
        payload(ctx,h.id,"INPUT",name,input);
        return h;
    }
    public UUID payload(ObservationContext ctx,UUID span,String kind,String name,Object body) {
        String safe=redactor.redact(body);
        if("[UNSERIALIZABLE]".equals(safe))markIncomplete(ctx.runId());
        UUID payloadId=UUID.randomUUID();
        write(ctx.runId(),()->jdbc.update("INSERT INTO assessment_payloads(id,run_id,span_id,kind,name,body) VALUES (?,?,?,?,?,?)",
            payloadId,ctx.runId(),span,kind,name,safe));
        return payloadId;
    }
    private record ArtifactSnapshot(UUID id,String body) {}
    private static final class ArtifactState {
        final String prefix;
        final Map<String,ArtifactSnapshot> snapshots=new ConcurrentHashMap<>();
        ArtifactState(String session){prefix="tasks/"+session+"/";}
    }
    private final Map<UUID,ArtifactState> artifactStates=new ConcurrentHashMap<>();

    private ArtifactSnapshot planningSnapshot(ArtifactState state) {
        if (state == null) return null;
        Map<String, String> bodies = new HashMap<>();
        state.snapshots.forEach((name, snapshot) -> bodies.put(name, snapshot.body()));
        String name = PlanningArtifacts.name(bodies);
        return name == null ? null : state.snapshots.get(name);
    }

    /** Called only after the actual filesystem tool reports a successful mutation, with its operation ID. */
    void recordArtifactMutation(ObservationContext ctx,UUID operation,String path,String body){
        ArtifactState state=artifactStates.get(ctx.runId());
        if(state==null||path==null||body==null||!"PLANNING".equals(ctx.purpose()))return;
        String normalized=path.replace('\\','/');
        if(normalized.startsWith("./"))normalized=normalized.substring(2);
        int offset=normalized.startsWith(state.prefix)?0:normalized.indexOf("/"+state.prefix);
        if(offset<0)return;
        if(!normalized.startsWith(state.prefix))offset++;
        String name=normalized.substring(offset+state.prefix.length());
        if(name.isBlank()||name.contains("..")||!(name.endsWith(".md")||name.endsWith(".json")||name.endsWith(".txt")))return;
        synchronized(state){
            if(state.snapshots.size()>=1024&&!state.snapshots.containsKey(name)){markIncomplete(ctx.runId());return;}
            UUID payloadId=payload(ctx,operation,"ARTIFACT",name,body);
            state.snapshots.put(name,new ArtifactSnapshot(payloadId,body));
        }
    }
    /** No file-existence or content-difference inference: only this execution's successful write/edit operations. */
    public Map<String,String> currentArtifacts(ObservationContext ctx){
        if(ambiguousArtifacts.contains(ctx.runId()))return Map.of();
        ArtifactState state=artifactStates.get(ctx.runId());
        if(state==null)return Map.of();
        Map<String,String> bodies=new HashMap<>();
        state.snapshots.forEach((name,snapshot)->bodies.put(name,snapshot.body()));
        return Map.copyOf(bodies);
    }
    public String planningArtifact(ObservationContext ctx){return PlanningArtifacts.text(currentArtifacts(ctx));}
    void artifactCaptureFailed(ObservationContext ctx){
        ArtifactState state=artifactStates.get(ctx.runId());if(state!=null)state.snapshots.clear();
        markIncomplete(ctx.runId());
    }

    public final class Handle {
        public final UUID id=UUID.randomUUID();
        public final ObservationContext context;
        private final Span span;
        private final AtomicBoolean ended=new AtomicBoolean();
        private final AtomicBoolean ttftWritten=new AtomicBoolean();
        private Handle(ObservationContext ctx,String kind,String name,String callId) {
            context=ctx;
            var builder=tracer.spanBuilder(name==null?kind:name).setNoParent()
                .setAttribute(AssessmentTraceProcessor.key("run_id"),ctx.runId().toString())
                .setAttribute(AssessmentTraceProcessor.key("node_id"),id.toString())
                .setAttribute(AssessmentTraceProcessor.key("kind"),kind)
                .setAttribute(AssessmentTraceProcessor.key("turn_id"),ctx.turnId().toString())
                .setAttribute(AssessmentTraceProcessor.key("source"),ctx.sampleId()==null?"ONLINE":"ASSESSMENT")
                .setAttribute(AssessmentTraceProcessor.key("phase"),ctx.purpose());
            if(ctx.sampleId()!=null)builder.setAttribute(AssessmentTraceProcessor.key("sample_id"),ctx.sampleId().toString());
            if(ctx.parentId()!=null)builder.setAttribute(AssessmentTraceProcessor.key("parent_id"),ctx.parentId().toString());
            if(callId!=null)builder.setAttribute(AssessmentTraceProcessor.key("call_id"),callId);
            if(ctx.spanContext()!=null&&ctx.spanContext().isValid()){
                builder.setParent(Context.root().with(Span.wrap(ctx.spanContext())));
                builder.setAttribute(AssessmentTraceProcessor.key("root_span_id"),ctx.spanContext().getSpanId());
            }
            span=enabled()?builder.startSpan():Span.getInvalid();
        }
        public ObservationContext child(){return context.child(id,span.getSpanContext());}
        public void attempt(UUID group,int index){attr(span,"attempt_group",group);attr(span,"attempt_index",index);}
        public void ttft(double ms){if(ttftWritten.compareAndSet(false,true))attr(span,"ttft_ms",ms);}
        public void end(String status,Object output,Throwable error) {
            if(!ended.compareAndSet(false,true))return;
            attr(span,"status",status);
            if(error!=null){attr(span,"error_type",error.getClass().getSimpleName());span.setStatus(StatusCode.ERROR);}
            else if(Set.of("FAILED","ERROR","TIMED_OUT","INTERRUPTED").contains(status))span.setStatus(StatusCode.ERROR);
            span.end();
            if(output!=null)payload(context,id,"OUTPUT",null,output);
            if(error!=null)payload(context,id,"ERROR",null,Map.of("type",error.getClass().getSimpleName(),"message",Objects.toString(error.getMessage(),"")));
        }
        public void usage(String model, io.agentscope.core.model.ChatUsage usage, String requestId) {
            var price=properties.getPrices().get(model);
            boolean complete=usage!=null && usage.getInputTokens()>0 && usage.getOutputTokens()>0 && price!=null && price.getInputPerMillion()!=null && price.getOutputPerMillion()!=null && (usage.getCachedTokens()==0||price.getCachedInputPerMillion()!=null) && usage.getCachedTokens()<=usage.getInputTokens();
            java.math.BigDecimal cost=complete ? price.getInputPerMillion().multiply(java.math.BigDecimal.valueOf(usage.getInputTokens()-usage.getCachedTokens()))
                .add(usage.getCachedTokens()>0?price.getCachedInputPerMillion().multiply(java.math.BigDecimal.valueOf(usage.getCachedTokens())):java.math.BigDecimal.ZERO)
                .add(price.getOutputPerMillion().multiply(java.math.BigDecimal.valueOf(usage.getOutputTokens()))).movePointLeft(6):null;
            attr(span,"model",model);
            attr(span,"input_tokens",usage==null||usage.getInputTokens()==0?null:usage.getInputTokens());
            attr(span,"output_tokens",usage==null||usage.getOutputTokens()==0?null:usage.getOutputTokens());
            attr(span,"cached_tokens",usage==null||usage.getCachedTokens()==0?null:usage.getCachedTokens());
            attr(span,"usage_kind",usage==null?"UNKNOWN":usage.getInputTokens()==0||usage.getOutputTokens()==0?"PARTIAL":"ACTUAL");
            attr(span,"request_id",requestId);attr(span,"cost",cost);attr(span,"cost_complete",complete);
            attr(span,"price_version",properties.getPriceVersion());
        }
    }
    @Scheduled(fixedDelay=30000)
    public void markIncomplete() {
        if(enabled())processor.persistIncomplete();
    }
    @Scheduled(fixedDelay=3600000, initialDelay=60000)
    public void retain() {
        if(!properties.isRetentionEnabled()) return;
        try {
            jdbc.update("DELETE FROM assessment_payloads WHERE created_at < now()-interval '30 days'");
            jdbc.update("DELETE FROM assessment_runs WHERE started_at < now()-interval '180 days'");
            jdbc.update("DELETE FROM assessment_batches WHERE created_at < now()-interval '180 days'");
        } catch(Exception e) { LoggerFactory.getLogger(getClass()).warn("Assessment retention unavailable: {}",e.getClass().getSimpleName()); }
    }
    public void flush() throws Exception { processor.flush(); }
    /** Inspect the persisted run as well: an earlier recorded gap must not disappear after queue recovery. */
    public void flush(ObservationContext ctx) throws Exception {
        processor.flush();
        if(!enabled())return;
        var rows=jdbc.queryForList("""
            SELECT r.data_complete FROM assessment_runs r WHERE r.id=? OR (?::uuid IS NOT NULL AND r.sample_id=?)
            """,ctx.runId(),ctx.sampleId(),ctx.sampleId());
        if(rows.isEmpty()||rows.stream().anyMatch(r->!Boolean.TRUE.equals(r.get("data_complete"))))
            throw new IllegalStateException("Trace run is incomplete");
        var running=jdbc.queryForList("SELECT id FROM assessment_spans WHERE run_id=? AND status='RUNNING' LIMIT 1",ctx.runId());
        if(!running.isEmpty())throw new IllegalStateException("Trace nodes are still running");
    }
    @PreDestroy public void close() {
        roots.forEach((id,span)->{attr(span,"status","INTERRUPTED");attr(span,"data_complete",false);span.end();});
        roots.clear();
        if(ownedProvider!=null)ownedProvider.close();
    }
}
