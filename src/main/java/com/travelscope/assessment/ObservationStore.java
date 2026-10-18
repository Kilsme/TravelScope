package com.travelscope.assessment;

import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import java.sql.Timestamp;
import java.time.Instant;
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
    private final ThreadPoolExecutor writer;
    private final AtomicBoolean ingestionDegraded=new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicLong droppedWrites=new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong persistenceFailures=new java.util.concurrent.atomic.AtomicLong();
    public Map<String,Object> health(){return Map.of("enabled",enabled(),"queueDepth",writer.getQueue().size(),"droppedWrites",droppedWrites.get(),"persistenceFailures",persistenceFailures.get(),"pendingIncompleteRuns",incomplete.size(),"degraded",ingestionDegraded.get());}
    private final Set<UUID> incomplete = ConcurrentHashMap.newKeySet();
    public ObservationStore(JdbcTemplate jdbc, AssessmentProperties properties, PayloadRedactor redactor) {
        this.jdbc=jdbc; this.properties=properties; this.redactor=redactor;
        writer=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(properties.getQueueCapacity()),
            r -> { Thread t=new Thread(r,"assessment-writer"); t.setDaemon(true); return t; },new ThreadPoolExecutor.AbortPolicy());
    }
    private void markIncomplete(UUID run){
        if(incomplete.size()<Math.max(1,properties.getQueueCapacity()))incomplete.add(run);
        else ingestionDegraded.set(true); // bounded failure metadata; conservative completeness until restart
    }
    private final Map<String,Set<UUID>> activeSessions=new ConcurrentHashMap<>();
    private final Map<UUID,String> runSessions=new ConcurrentHashMap<>();
    private final Set<UUID> ambiguousArtifacts=ConcurrentHashMap.newKeySet();
    private void registerSession(String session,UUID run){
        if(session==null)return;
        if(runSessions.size()>=properties.getQueueCapacity()){ingestionDegraded.set(true);return;}
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
        UUID id=UUID.randomUUID();
        ObservationContext ctx=new ObservationContext(id,UUID.randomUUID(),traceId,null,sample);
        if(enabled()&&sample==null)registerSession(session,id);
        if(enabled()){
            if(artifactStates.size()<properties.getQueueCapacity())artifactStates.put(id,new ArtifactState(session));
            else markIncomplete(id);
        }
        write(id, () -> jdbc.update("INSERT INTO assessment_runs(id,turn_id,trace_id,session_id,sample_id,source,status) VALUES (?,?,?,?,?,?,?)",
            id,ctx.turnId(),ctx.traceId(),session,sample,sample==null?"ONLINE":"ASSESSMENT","RUNNING"));
        return ctx;
    }
    public void write(UUID run, Runnable action) {
        if (!enabled()) return;
        try { writer.execute(() -> { try { action.run(); } catch(Exception e) { markIncomplete(run);persistenceFailures.incrementAndGet();
            LoggerFactory.getLogger(getClass()).warn("Assessment persistence failed: {}",e.getClass().getSimpleName()); } }); }
        catch (RejectedExecutionException e) { markIncomplete(run);droppedWrites.incrementAndGet(); }
    }
    private final Set<UUID> timedOut=ConcurrentHashMap.newKeySet();
    public void timeout(ObservationContext ctx){timedOut.add(ctx.runId());}
    public void finish(ObservationContext ctx, String status, double ms, boolean artifacts) {
        final boolean ambiguous=ambiguousArtifacts.remove(ctx.runId());
        ArtifactState artifactsForRun=artifactStates.remove(ctx.runId());
        final ArtifactSnapshot plan=planningSnapshot(artifactsForRun);
        final boolean ownedPlan=plan!=null;
        final UUID finalArtifactId=artifacts&&ownedPlan&&!ambiguous?plan.id():null;
        String session=runSessions.remove(ctx.runId());
        if(session!=null)activeSessions.computeIfPresent(session,(key,active)->{active.remove(ctx.runId());return active.isEmpty()?null:active;});
        final String terminalStatus=timedOut.remove(ctx.runId())?"TIMED_OUT":(ambiguous||!ownedPlan)&&"PLANNING_COMPLETED".equals(status)?"INCOMPLETE":status;
        write(ctx.runId(), () -> jdbc.update("UPDATE assessment_runs SET status=CASE WHEN ?='PLANNING_COMPLETED' AND EXISTS(SELECT 1 FROM assessment_spans WHERE run_id=? AND status='RUNNING' AND phase='PLANNING') THEN 'INCOMPLETE' WHEN ?='PLANNING_COMPLETED' AND NOT EXISTS(SELECT 1 FROM assessment_spans WHERE run_id=? AND kind='AGENT' AND name='planning-agent' AND status='SUCCEEDED') THEN 'INCOMPLETE' ELSE ? END,ended_at=now(),duration_ms=?,artifact_available=?,data_complete=data_complete AND ?,planning_artifact_id=? WHERE id=?",
            terminalStatus,ctx.runId(),terminalStatus,ctx.runId(),terminalStatus,ms,artifacts&&ownedPlan&&!ambiguous,!ambiguous&&!ingestionDegraded.get()&&!incomplete.contains(ctx.runId()),finalArtifactId,ctx.runId()));
    }
    void attempt(UUID spanId,UUID group,int index){jdbc.update("UPDATE assessment_spans SET attempt_group=?,attempt_index=? WHERE id=?",group,index,spanId);}
    public void queue(ObservationContext ctx,double ms){write(ctx.runId(),()->jdbc.update("UPDATE assessment_runs SET queue_ms=? WHERE id=?",ms,ctx.runId()));}
    public void task(ObservationContext ctx,String type) {
        write(ctx.runId(),()->jdbc.update("UPDATE assessment_runs SET task_type=? WHERE id=?",type,ctx.runId()));
    }
    public Handle span(ObservationContext ctx,String kind,String name,String callId,Object input) {
        Handle h=new Handle(ctx,kind,name,callId);
        write(ctx.runId(),()->jdbc.update("INSERT INTO assessment_spans(id,run_id,parent_id,kind,name,call_id,status,started_at,phase) VALUES (?,?,?,?,?,?,'RUNNING',?,?)",
            h.id,ctx.runId(),ctx.parentId(),kind,name,callId,Timestamp.from(h.started),ctx.purpose()));
        if("TOOL".equals(kind)){
            boolean local=FixtureTools.local(name);
            java.math.BigDecimal fee=properties.getToolPrices().get(name);
            write(ctx.runId(),()->jdbc.update("UPDATE assessment_spans SET cost=?,cost_complete=?,price_version=? WHERE id=?",local?java.math.BigDecimal.ZERO:fee,local||fee!=null,properties.getPriceVersion(),h.id));
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
        private final Instant started=Instant.now();
        private final long tick=System.nanoTime();
        private final AtomicBoolean ended=new AtomicBoolean();
        private Handle(ObservationContext ctx,String kind,String name,String callId) { context=ctx; }
        public ObservationContext child() { return context.child(id); }
        public void end(String status,Object output,Throwable error) {
            if (!ended.compareAndSet(false,true)) return;
            double ms=(System.nanoTime()-tick)/1_000_000d;
            write(context.runId(),()->jdbc.update("UPDATE assessment_spans SET status=?,ended_at=now(),duration_ms=?,error_type=? WHERE id=?",
                status,ms,error==null?null:error.getClass().getSimpleName(),id));
            if(output!=null) payload(context,id,"OUTPUT",null,output);
            if(error!=null) payload(context,id,"ERROR",null,Map.of("type",error.getClass().getSimpleName(),"message",Objects.toString(error.getMessage(),"")));
        }
        public void usage(String model, io.agentscope.core.model.ChatUsage usage, String requestId) {
            var price=properties.getPrices().get(model);
            boolean complete=usage!=null && usage.getInputTokens()>0 && usage.getOutputTokens()>0 && price!=null && price.getInputPerMillion()!=null && price.getOutputPerMillion()!=null && (usage.getCachedTokens()==0||price.getCachedInputPerMillion()!=null) && usage.getCachedTokens()<=usage.getInputTokens();
            java.math.BigDecimal cost=complete ? price.getInputPerMillion().multiply(java.math.BigDecimal.valueOf(usage.getInputTokens()-usage.getCachedTokens()))
                .add(usage.getCachedTokens()>0?price.getCachedInputPerMillion().multiply(java.math.BigDecimal.valueOf(usage.getCachedTokens())):java.math.BigDecimal.ZERO)
                .add(price.getOutputPerMillion().multiply(java.math.BigDecimal.valueOf(usage.getOutputTokens()))).movePointLeft(6):null;
            write(context.runId(),()->jdbc.update("UPDATE assessment_spans SET model=?,input_tokens=?,output_tokens=?,cached_tokens=?,usage_kind=?,request_id=?,cost=?,cost_complete=?,price_version=? WHERE id=?",
                model,usage==null||usage.getInputTokens()==0?null:usage.getInputTokens(),usage==null||usage.getOutputTokens()==0?null:usage.getOutputTokens(),usage==null||usage.getCachedTokens()==0?null:usage.getCachedTokens(),
                usage==null?"UNKNOWN":usage.getInputTokens()==0||usage.getOutputTokens()==0?"PARTIAL":"ACTUAL",requestId,cost,complete,properties.getPriceVersion(),id));
        }
    }
    @Scheduled(fixedDelay=30000)
    public void markIncomplete() {
        if(!enabled()) return;
        for(UUID id:List.copyOf(incomplete)) {
            try { if(jdbc.update("UPDATE assessment_runs SET data_complete=false WHERE id=?",id)>0) incomplete.remove(id); }
            catch(Exception ignored) { break; }
        }
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
    public void flush() throws Exception { writer.submit(()->{}).get(10,TimeUnit.SECONDS); }
    @PreDestroy public void close() throws InterruptedException { writer.shutdown(); writer.awaitTermination(5,TimeUnit.SECONDS); }
}
