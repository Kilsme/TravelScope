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
        return Map.of("baseline",a,"candidate",b,"configurationDiff",!Objects.equals(a.get("config_json"),b.get("config_json")),"scenarios",jdbc.queryForList("""
            WITH per_sample AS (
                SELECT s.*,r.duration_ms AS final_round_ms,r.status AS final_status,r.artifact_available,
                  (SELECT sum(duration_ms) FROM assessment_runs all_runs WHERE all_runs.sample_id=s.id) AS processing_ms,
                  (SELECT sum(p.cost) FROM assessment_spans p JOIN assessment_runs ar ON ar.id=p.run_id WHERE ar.sample_id=s.id AND p.kind IN ('MODEL','TOOL')) AS known_cost,
                  (SELECT bool_and(p.cost_complete) FROM assessment_spans p JOIN assessment_runs ar ON ar.id=p.run_id WHERE ar.sample_id=s.id AND p.kind IN ('MODEL','TOOL')) AS cost_complete
                FROM assessment_samples s LEFT JOIN LATERAL (SELECT * FROM assessment_runs WHERE sample_id=s.id ORDER BY started_at DESC LIMIT 1) r ON true
                WHERE s.batch_id IN (?,?) AND s.case_id IN (SELECT case_id FROM assessment_samples WHERE batch_id=? INTERSECT SELECT case_id FROM assessment_samples WHERE batch_id=?)
            )
            SELECT batch_id,case_id,count(*) AS samples,
              count(*) FILTER(WHERE status='SUCCEEDED')::float/count(*) AS execution_success_rate,
              count(*) FILTER(WHERE score_status='ERROR') AS score_errors,
              count(*) FILTER(WHERE final_status='TIMED_OUT')::float/count(*) AS timeout_rate,
              count(*) FILTER(WHERE final_status='PLANNING_COMPLETED' AND artifact_available) AS completed_plans,
              percentile_cont(0.5) WITHIN GROUP(ORDER BY final_round_ms) FILTER(WHERE final_status='PLANNING_COMPLETED' AND artifact_available) AS p50_ms,
              percentile_cont(0.95) WITHIN GROUP(ORDER BY final_round_ms) FILTER(WHERE final_status='PLANNING_COMPLETED' AND artifact_available) AS p95_ms,
              avg(final_round_ms) FILTER(WHERE final_status='PLANNING_COMPLETED' AND artifact_available) AS mean_ms,
              stddev_pop(final_round_ms) FILTER(WHERE final_status='PLANNING_COMPLETED' AND artifact_available) AS stddev_ms,
              avg(final_round_ms) FILTER(WHERE final_status IN ('FAILED','CANCELLED','INTERRUPTED','TIMED_OUT')) AS failed_mean_ms,
              sum(processing_ms) AS system_time_ms,sum(known_cost) AS known_cost,bool_and(cost_complete) AS cost_complete,
              count(*) FILTER(WHERE score_status='SUCCEEDED' AND (score_json::jsonb->>'passed')::boolean)::float/count(*) AS score_pass_rate,
              count(*) FILTER(WHERE rule_json IS NOT NULL AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(rule_json::jsonb) rule WHERE rule->>'status'<>'PASS'))::float/count(*) AS rule_pass_rate,
              count(*) FILTER(WHERE status='SUCCEEDED' AND score_status='SUCCEEDED' AND (score_json::jsonb->>'passed')::boolean AND rule_json IS NOT NULL AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(rule_json::jsonb) rule WHERE rule->>'status'<>'PASS'))::float/count(*) AS overall_pass_rate
            FROM per_sample GROUP BY batch_id,case_id ORDER BY case_id,batch_id
            """,baseline,candidate,baseline,candidate));
    }
    @PreDestroy public void close(){worker.shutdownNow();}
}
