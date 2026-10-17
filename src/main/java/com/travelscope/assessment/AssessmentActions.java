package com.travelscope.assessment;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
@Service
public class AssessmentActions {
    private final AssessmentProperties properties;
    private final AssessmentQueries queries;private final PosthocScorer scorer;private final JdbcTemplate jdbc;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(10),r->{Thread t=new Thread(r,"assessment-manual-score");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public AssessmentActions(AssessmentQueries queries,PosthocScorer scorer,JdbcTemplate jdbc,AssessmentProperties properties){this.properties=properties;this.queries=queries;this.scorer=scorer;this.jdbc=jdbc;}
    public Object evaluate(UUID id){
        if(!properties.isEnabled())throw new ResponseStatusException(HttpStatus.CONFLICT,"评测开关未启用");
        var run=queries.run(id);
        if(!"ONLINE".equals(run.get("source"))||!"PLANNING_COMPLETED".equals(run.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"仅可评分已完成的日常规划");
        var artifact=queries.finalPlanningArtifact(id).orElseThrow(()->new ResponseStatusException(HttpStatus.GONE,"最终产物未记录、已过留存期或不可获取"));
        var ctx=new ObservationContext(id,(UUID)run.get("turn_id"),(String)run.get("trace_id"),null,null);
        var inputs=jdbc.queryForList("SELECT p.body FROM assessment_payloads p JOIN assessment_spans s ON s.id=p.span_id WHERE p.run_id=? AND s.kind='AGENT' AND s.parent_id IS NULL AND p.kind='INPUT' AND p.created_at>=now()-interval '30 days' ORDER BY p.created_at LIMIT 1",id);
        if(inputs.isEmpty())throw new ResponseStatusException(HttpStatus.GONE,"需求正文不可获取");
        try{worker.execute(()->scorer.score(ctx,(String)inputs.getFirst().get("body"),(String)artifact.get("body")));}
        catch(RejectedExecutionException e){throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);}
        return Map.of("runId",id,"status","QUEUED");
    }
    public Object evaluations(UUID id){queries.run(id);return jdbc.queryForList("SELECT * FROM assessment_evaluations WHERE run_id=? ORDER BY created_at DESC",id);}
    public Object compare(UUID baseline,UUID candidate){
        var a=queries.one("SELECT id,tool_mode,config_json FROM assessment_batches WHERE id=?",baseline);
        var b=queries.one("SELECT id,tool_mode,config_json FROM assessment_batches WHERE id=?",candidate);
        if(!a.get("tool_mode").equals(b.get("tool_mode")))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"LIVE 与 FIXTURE 不可混合比较");
        return Map.of("baseline",a,"candidate",b,"configurationDiff",!Objects.equals(a.get("config_json"),b.get("config_json")),
            "scenarios",queries.comparisonMetrics(baseline,candidate),"components",queries.comparisonComponents(baseline,candidate));
    }
    @PreDestroy public void close(){worker.shutdownNow();}
}
