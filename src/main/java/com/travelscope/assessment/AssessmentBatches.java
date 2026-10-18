package com.travelscope.assessment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelscope.eval.*;
import com.travelscope.service.ChatService;
import com.travelscope.collab.TaskWorkspace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import jakarta.annotation.PreDestroy;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
@Service
public class AssessmentBatches {
    private final JdbcTemplate jdbc; private final ObjectMapper mapper; private final AssessmentProperties properties;
    private final ChatService chat; private final ObservationStore store; private final TaskWorkspace workspace;
    private final PosthocScorer scorer; private final AssessmentQueries queries;
    private final com.travelscope.config.AppProperties app;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"assessment-batches");t.setDaemon(true);return t;});
    private final java.util.concurrent.atomic.AtomicBoolean workerActive=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile io.agentscope.harness.agent.HarnessAgent activeAgent;
    private volatile Future<?> running; private volatile UUID runningId;
    public AssessmentBatches(JdbcTemplate jdbc,ObjectMapper mapper,AssessmentProperties properties,ChatService chat,ObservationStore store,TaskWorkspace workspace,PosthocScorer scorer,AssessmentQueries queries,com.travelscope.config.AppProperties app){
        this.jdbc=jdbc;this.mapper=mapper;this.properties=properties;this.chat=chat;this.store=store;this.workspace=workspace;this.scorer=scorer;this.queries=queries;this.app=app;
    }
    private String codeVersion(){
        String supplied=System.getProperty("assessment.codeVersion");if(supplied!=null&&!supplied.isBlank())return supplied;
        try {Process process=new ProcessBuilder("git","rev-parse","HEAD").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(!process.waitFor(2,TimeUnit.SECONDS)){process.destroyForcibly();return "UNKNOWN";}
            return new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();
        }catch(Exception e){return "UNKNOWN";}
    }
    private String sha256(String text){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){return "UNKNOWN";}}
    private String codeFingerprint(){
        try{var digest=java.security.MessageDigest.getInstance("SHA-256");for(Class<?> type:List.of(AssessmentBatches.class,ObservationMiddleware.class,ObservedModel.class,ChatService.class,AssessmentRules.class,PosthocScorer.class,PlanningArtifacts.class,FixtureServices.class,com.travelscope.service.workflow.RequirementWorkflowService.class,com.travelscope.service.retrieval.PoiRetrievalService.class,com.travelscope.service.route.RoutePlannerServiceImpl.class)){
            try(var in=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")){if(in==null)return "UNKNOWN";digest.update(in.readAllBytes());}}
            return java.util.HexFormat.of().formatHex(digest.digest());
        }catch(Exception e){return "UNKNOWN";}
    }
    private String skillVersion(){
        try{var digest=java.security.MessageDigest.getInstance("SHA-256");var resources=new org.springframework.core.io.support.PathMatchingResourcePatternResolver().getResources("classpath*:skills/**/SKILL.md");
            java.util.Arrays.sort(resources,Comparator.comparing(org.springframework.core.io.Resource::getDescription));
            for(var resource:resources)try(var in=resource.getInputStream()){digest.update(in.readAllBytes());}
            return java.util.HexFormat.of().formatHex(digest.digest());
        }catch(Exception e){return "UNKNOWN";}
    }
    public record Request(List<String> caseIds,String toolMode,Integer repetitions,String requestKey){}
    public Object cases(){return EvalCaseLoader.loadAll();}
    private String json(Object o){try{return mapper.writeValueAsString(o);}catch(Exception e){throw new IllegalArgumentException(e);}}
    @Transactional
    public synchronized UUID create(Request request){
        if(!properties.isEnabled()||!properties.isBatchesEnabled())throw new ResponseStatusException(HttpStatus.CONFLICT,"评测开关未启用");
        if(request.requestKey()==null||!request.requestKey().matches("[A-Za-z0-9_-]{8,128}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请求键无效");
        // Serialize duplicate keys until the surrounding transaction commits, including across app instances.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))",request.requestKey());
        var old=jdbc.queryForList("SELECT id FROM assessment_batches WHERE request_key=?",request.requestKey());
        if(!old.isEmpty())return (UUID)old.getFirst().get("id");
        int count=request.repetitions()==null?3:request.repetitions();
        if(count<1||count>10||request.toolMode()==null||!Set.of("LIVE","FIXTURE").contains(request.toolMode())||request.caseIds()==null||request.caseIds().isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var selected=EvalCaseLoader.loadAll().stream().filter(c->request.caseIds().contains(c.caseId())).toList();
        if(selected.size()!=new HashSet<>(request.caseIds()).size())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"用例不存在");
        if(!jdbc.queryForList("SELECT id FROM assessment_batches WHERE status IN ('QUEUED','RUNNING','CANCELLING')").isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"已有批次在执行");
        UUID id=UUID.randomUUID();LocalDate base=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        Map<String,Object> versions=new LinkedHashMap<>();versions.put("dateBase",base.toString());versions.put("datePolicy",CaseFreezer.VERSION);versions.put("scorer",PosthocScorer.VERSION);versions.put("rules","rules-v1");versions.put("priceVersion",properties.getPriceVersion());
        versions.put("codeVersion",codeVersion());versions.put("codeFingerprint",codeFingerprint());versions.put("models",Map.of("primary",app.getModelGateway().getModel(),"fallback",app.getIntentCascade().getL2Model(),"reviewer",app.getModelGateway().getReviewerModel(),"thinkingEnabled",app.getModelGateway().isThinkingEnabled()));
        versions.put("promptVersion",sha256(com.travelscope.agent.TravelMasterAgent.SYS_PROMPT+com.travelscope.agent.ItineraryAgent.SYS_PROMPT+com.travelscope.agent.ReviewerAgent.SYS_PROMPT));
        versions.put("skillVersion",skillVersion());versions.put("caseVersion",sha256(json(selected)));versions.put("cases",selected);versions.put("toolMode",request.toolMode());
        try { jdbc.update("INSERT INTO assessment_batches(id,request_key,status,tool_mode,repetitions,config_json) VALUES (?,?,'QUEUED',?,?,?)",id,request.requestKey(),request.toolMode(),count,json(versions)); } catch(org.springframework.dao.DataIntegrityViolationException e) { throw new ResponseStatusException(HttpStatus.CONFLICT,"已有活跃批次或重复请求",e); }
        for(var c:selected)for(int i=1;i<=count;i++){
            UUID sample=UUID.randomUUID();
            var frozen=CaseFreezer.freeze(c,base);
            jdbc.update("INSERT INTO assessment_samples(id,batch_id,case_id,repetition,case_json,session_id) VALUES (?,?,?,?,?,?)",sample,id,c.caseId(),i,json(frozen),"eval-"+sample);
        }
        return id;
    }
    public List<Map<String,Object>> list(){return jdbc.queryForList("SELECT id,status,tool_mode,repetitions,created_at,ended_at FROM assessment_batches ORDER BY created_at DESC LIMIT 100");}
    public Object detail(UUID id){return Map.of("batch",queries.one("SELECT * FROM assessment_batches WHERE id=?",id),"samples",jdbc.queryForList("SELECT id,case_id,repetition,status,rule_json,score_status,score_json FROM assessment_samples WHERE batch_id=? ORDER BY case_id,repetition",id),"metrics",queries.batchMetrics(id));}
    @EventListener(ApplicationReadyEvent.class)
    public void recover(){
        if(!properties.isEnabled())return;
        try{
            jdbc.update("UPDATE assessment_runs SET status='INTERRUPTED',ended_at=now(),data_complete=false WHERE status='RUNNING'");
            jdbc.update("UPDATE assessment_spans SET status='INTERRUPTED',ended_at=now() WHERE status='RUNNING'");
            jdbc.update("UPDATE assessment_samples SET status='INTERRUPTED',score_status=CASE WHEN score_status='RUNNING' THEN 'ERROR' ELSE score_status END WHERE status='RUNNING'");
            jdbc.update("UPDATE assessment_evaluations SET status='ERROR' WHERE status='RUNNING'");
            jdbc.update("UPDATE assessment_batches SET status='INTERRUPTED',ended_at=now() WHERE status IN ('RUNNING','QUEUED','CANCELLING')");
        }catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Assessment schema unavailable; apply upgrade before enabling");}
    }
    @Scheduled(fixedDelay=1000)
    public synchronized void dispatch(){
        if(!properties.isBatchesEnabled()||!properties.isEnabled()||workerActive.get())return;
        var queued=jdbc.queryForList("SELECT id FROM assessment_batches WHERE status='QUEUED' ORDER BY created_at LIMIT 1");
        if(queued.isEmpty())return;
        UUID id=(UUID)queued.getFirst().get("id");
        if(jdbc.update("UPDATE assessment_batches SET status='RUNNING' WHERE id=? AND status='QUEUED'",id)!=1)return;
        runningId=id;workerActive.set(true);running=executor.submit(()->{try{execute(id);}finally{workerActive.set(false);}});
    }
    public synchronized void cancel(UUID id){
        queries.one("SELECT id FROM assessment_batches WHERE id=?",id);
        jdbc.update("UPDATE assessment_batches SET status='CANCELLING',cancel_requested=true WHERE id=? AND status IN ('QUEUED','RUNNING')",id);
        if(id.equals(runningId)&&running!=null){if(activeAgent!=null)activeAgent.interrupt();running.cancel(true);}
        jdbc.update("UPDATE assessment_samples SET status='CANCELLED' WHERE batch_id=? AND status IN ('QUEUED','RUNNING')",id);
        jdbc.update("UPDATE assessment_batches SET status='CANCELLED',ended_at=now() WHERE id=? AND status='CANCELLING'",id);
    }
    @Transactional public void retry(UUID id){
        var batch=queries.one("SELECT * FROM assessment_batches WHERE id=?",id);
        if(!Set.of("INTERRUPTED","CANCELLED","COMPLETED").contains(batch.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT);
        if(!jdbc.queryForList("SELECT id FROM assessment_batches WHERE status IN ('QUEUED','RUNNING','CANCELLING')").isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT);
        jdbc.update("UPDATE assessment_samples SET status='QUEUED',score_status='PENDING',rule_json=null,score_json=null WHERE batch_id=? AND status IN ('INTERRUPTED','CANCELLED','QUEUED')",id);
        jdbc.update("UPDATE assessment_batches SET status='QUEUED',cancel_requested=false,ended_at=null WHERE id=?",id);
    }
    private void execute(UUID batchId){
        try{
            var batch=queries.one("SELECT * FROM assessment_batches WHERE id=?",batchId);
            for(var sample:jdbc.queryForList("SELECT * FROM assessment_samples WHERE batch_id=? AND status='QUEUED' ORDER BY case_id,repetition",batchId)){
                if(Thread.currentThread().isInterrupted()||Boolean.TRUE.equals(queries.one("SELECT cancel_requested FROM assessment_batches WHERE id=?",batchId).get("cancel_requested")))break;
                runSample(sample,(String)batch.get("tool_mode"));
            }
            jdbc.update("UPDATE assessment_batches SET status='COMPLETED',ended_at=now() WHERE id=? AND status='RUNNING'",batchId);
        }catch(Exception e){jdbc.update("UPDATE assessment_batches SET status='INTERRUPTED',ended_at=now() WHERE id=? AND status='RUNNING'",batchId);}
    }
    private void runSample(Map<String,Object> sample,String mode){
        UUID sampleId=(UUID)sample.get("id");
        // Negative IDs are outside generated database identities and isolate workspace / Agent state.
        long identity=-Math.max(1,UUID.randomUUID().getMostSignificantBits()>>>1);
        jdbc.update("UPDATE assessment_samples SET session_id=? WHERE id=?","conv-"+identity,sampleId);
        jdbc.update("UPDATE assessment_samples SET status='RUNNING' WHERE id=?",sampleId);
        ObservationContext last=null;String reply="";String artifact=null;Map<String,String> lastArtifacts=Map.of();
        try{
            EvalCase c=mapper.readValue((String)sample.get("case_json"),EvalCase.class);
            var isolatedAgent=chat.newAssessmentAgent();activeAgent=isolatedAgent;
            for(String message:c.userMessages()){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                last=store.begin("conv-"+identity,sampleId);long start=System.nanoTime();
                try{
                    reply=chat.executeAssessment(identity,message,last,mode,"EC-018".equals(c.caseId()),c.caseId(),isolatedAgent).block(Duration.ofMinutes(12));
                    lastArtifacts=store.currentArtifacts(last);
                    artifact=store.planningArtifact(last);
                    store.finish(last,artifact==null?(chat.assessmentWaiting(identity)?"WAITING_INPUT":"INCOMPLETE"):"PLANNING_COMPLETED",(System.nanoTime()-start)/1e6,artifact!=null);
                }catch(Exception e){store.finish(last,Thread.currentThread().isInterrupted()?"CANCELLED":"FAILED",(System.nanoTime()-start)/1e6,false);throw e;}
            }
            var dir=workspace.getTaskDir(""+identity,"conv-"+identity);
            var evidence=new EvalRunArtifact(reply,artifact,
                    lastArtifacts.getOrDefault("poi_shortlist.json",lastArtifacts.get("poi_shortlist.md")),
                    lastArtifacts.getOrDefault("route_plan.json",lastArtifacts.get("route_plan.md")),dir);
            store.flush();
            String executionStatus=(String)queries.one("SELECT status FROM assessment_runs WHERE id=?",last.runId()).get("status");
            if(Set.of("TIMED_OUT","FAILED","CANCELLED","INCOMPLETE").contains(executionStatus)) {
                jdbc.update("UPDATE assessment_samples SET status=?,score_status='SKIPPED' WHERE id=?",
                        "CANCELLED".equals(executionStatus)?"CANCELLED":"FAILED",sampleId);
                return;
            }
            var rules=AssessmentRules.check(c,evidence,jdbc.queryForList("SELECT s.* FROM assessment_spans s JOIN assessment_runs r ON r.id=s.run_id WHERE r.sample_id=?",sampleId),executionStatus);
            jdbc.update("UPDATE assessment_samples SET rule_json=?,score_status='RUNNING' WHERE id=?",json(rules),sampleId);
            if(artifact!=null){
                store.flush();
                UUID scoreId=scorer.score(last,String.join("\n",c.userMessages()),artifact);
                var score=queries.one("SELECT status,result_json FROM assessment_evaluations WHERE id=?",scoreId);
                jdbc.update("UPDATE assessment_samples SET score_status=?,score_json=? WHERE id=?",score.get("status"),score.get("result_json"),sampleId);
            }else jdbc.update("UPDATE assessment_samples SET score_status='SKIPPED' WHERE id=?",sampleId);
            jdbc.update("UPDATE assessment_samples SET status=? WHERE id=? AND status='RUNNING'",artifact==null&&!chat.assessmentWaiting(identity)?"FAILED":"SUCCEEDED",sampleId);
        }catch(Exception e){jdbc.update("UPDATE assessment_samples SET status=?,score_status=CASE WHEN score_status='RUNNING' THEN 'ERROR' ELSE score_status END WHERE id=? AND status='RUNNING'",Thread.currentThread().isInterrupted()?"CANCELLED":"FAILED",sampleId);}
        finally{var agent=activeAgent;activeAgent=null;if(agent!=null)agent.close();}
    }
    private String read(Path path){try{return Files.exists(path)?Files.readString(path):null;}catch(Exception e){return null;}}
    @PreDestroy public void close(){executor.shutdownNow();}
}
