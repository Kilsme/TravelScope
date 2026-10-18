package com.travelscope.assessment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
@Service
public class AssessmentQueries {
    private final JdbcTemplate jdbc;
    public AssessmentQueries(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<Map<String,Object>> runs(String source,int page,int size){return runs(source,page,size,null,null,null);}
    public List<Map<String,Object>> runs(String source,int page,int size,String taskType,String status,String session){
        return runs(source,page,size,taskType,status,session,null,null,null,null,null);
    }
    public List<Map<String,Object>> runs(String source,int page,int size,String taskType,String status,String session,String mode,String componentKind,String componentName,String phase,UUID batchId){
        if(mode!=null&&!mode.isBlank())AssessmentStatistics.scope(source,mode);
        if(componentKind!=null&&!Set.of("AGENT","MODEL","TOOL").contains(componentKind))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"组件类型无效");
        if(phase!=null&&!Set.of("PLANNING","SCORING").contains(phase))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"阶段无效");
        StringBuilder sql=new StringBuilder("""
          SELECT r.*,u.tokens,u.usage_complete,u.span_count,u.failed_span_count,
            (SELECT b.tool_mode FROM assessment_samples sm JOIN assessment_batches b ON b.id=sm.batch_id WHERE sm.id=r.sample_id) AS tool_mode,
            e.status AS latest_score_status,(e.result_json::jsonb->>'total')::float AS latest_score_total,
            (e.result_json::jsonb->>'passed')::boolean AS latest_score_passed
          FROM assessment_runs r
          LEFT JOIN LATERAL (
            SELECT CASE WHEN sum(input_tokens) FILTER(WHERE kind='MODEL') IS NULL AND sum(output_tokens) FILTER(WHERE kind='MODEL') IS NULL THEN NULL
              ELSE coalesce(sum(input_tokens) FILTER(WHERE kind='MODEL'),0)+coalesce(sum(output_tokens) FILTER(WHERE kind='MODEL'),0) END AS tokens,
              bool_and(usage_kind='ACTUAL' AND input_tokens IS NOT NULL AND output_tokens IS NOT NULL) FILTER(WHERE kind='MODEL') AS usage_complete,
              count(*) AS span_count,count(*) FILTER(WHERE status IN ('FAILED','ERROR','TIMED_OUT','INTERRUPTED') OR error_type IS NOT NULL) AS failed_span_count
            FROM assessment_spans WHERE run_id=r.id
          ) u ON true
          LEFT JOIN LATERAL (SELECT status,result_json FROM assessment_evaluations WHERE run_id=r.id ORDER BY created_at DESC,id DESC LIMIT 1) e ON true
          WHERE true
          """);
        List<Object> args=new ArrayList<>();
        String[] fields={"source","task_type","status","session_id"};String[] values={source,taskType,status,session};
        for(int i=0;i<fields.length;i++)if(values[i]!=null&&!values[i].isBlank()){sql.append(" AND r.").append(fields[i]).append("=?");args.add(values[i]);}
        if(mode!=null&&!mode.isBlank()){sql.append(" AND EXISTS(SELECT 1 FROM assessment_samples sm JOIN assessment_batches b ON b.id=sm.batch_id WHERE sm.id=r.sample_id AND b.tool_mode=?)");args.add(mode);}
        if(batchId!=null){sql.append(" AND EXISTS(SELECT 1 FROM assessment_samples sm WHERE sm.id=r.sample_id AND sm.batch_id=?)");args.add(batchId);}
        if(componentKind!=null||componentName!=null||phase!=null){
            sql.append(" AND EXISTS(SELECT 1 FROM assessment_spans sp WHERE sp.run_id=r.id");
            if(componentKind!=null){sql.append(" AND sp.kind=?");args.add(componentKind);}
            if(componentName!=null){sql.append(" AND (CASE WHEN sp.kind='MODEL' THEN coalesce(nullif(sp.model,''),sp.name) ELSE sp.name END)=?");args.add(componentName);}
            if(phase!=null){sql.append(" AND sp.phase=?");args.add(phase);}
            sql.append(")");
        }
        sql.append(" ORDER BY started_at DESC LIMIT ? OFFSET ?");args.add(size);args.add((long)page*size);
        return jdbc.queryForList(sql.toString(),args.toArray());
    }
    public List<Map<String,Object>> sampleRuns(UUID sample){return jdbc.queryForList("SELECT * FROM assessment_runs WHERE sample_id=? ORDER BY started_at DESC",sample);}
    public Map<String,Object> run(UUID id){return one("SELECT * FROM assessment_runs WHERE id=?",id);}
    public List<Map<String,Object>> spans(UUID id){run(id);return jdbc.queryForList("WITH RECURSIVE nodes AS (SELECT * FROM assessment_spans WHERE run_id=?)"+AssessmentStatistics.ROLLUPS+"SELECT * FROM measured ORDER BY started_at,id",id);}
    public List<Map<String,Object>> components(String source,String mode,String phase,String kind,UUID batchId){return new AssessmentStatistics(jdbc).components(source==null?"ONLINE":source,mode,phase,kind,batchId);}
    public Map<String,Object> batchMetrics(UUID id){var rows=new AssessmentStatistics(jdbc).sampleMetrics(id,null,false);return rows.isEmpty()?Map.of("samples",0,"completed_plans",0,"score_errors",0):rows.getFirst();}
    public List<Map<String,Object>> comparisonMetrics(UUID a,UUID b){return new AssessmentStatistics(jdbc).sampleMetrics(a,b,true);}
    public List<Map<String,Object>> comparisonComponents(UUID a,UUID b){return new AssessmentStatistics(jdbc).comparisonComponents(a,b);}
    public List<Map<String,Object>> payload(UUID id){return jdbc.queryForList("SELECT id,kind,name,body,created_at FROM assessment_payloads WHERE span_id=? AND created_at>=now()-interval '30 days' ORDER BY created_at",id);}
    public List<Map<String,Object>> artifacts(UUID id){run(id);return jdbc.queryForList("SELECT id,name,body,created_at FROM assessment_payloads WHERE run_id=? AND kind='ARTIFACT' AND created_at>=now()-interval '30 days'",id);}
    /** Read the exact snapshot selected at completion, never infer a final version from history order. */
    public Optional<Map<String,Object>> finalPlanningArtifact(UUID id){
        var rows=jdbc.queryForList("""
            SELECT p.id,p.name,p.body,p.created_at FROM assessment_runs r
            JOIN assessment_payloads p ON p.id=r.planning_artifact_id AND p.run_id=r.id
            WHERE r.id=? AND p.kind='ARTIFACT' AND p.name IN ('itinerary_draft.json','itinerary_draft.md')
              AND p.created_at>=now()-interval '30 days'
            """,id);
        return rows.stream().findFirst();
    }
    public Map<String,Object> one(String sql,Object... args){var rows=jdbc.queryForList(sql,args);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);return rows.getFirst();}
    public Map<String,Object> overview(String source){return overview(source,null);}
    /** Never emit an unlabelled mixed benchmark: default is ONLINE; assessment requires an explicit mode. */
    public Map<String,Object> overview(String source,String toolMode){
        String selectedSource=source==null||source.isBlank()?"ONLINE":source;
        String selectedMode=toolMode==null||toolMode.isBlank()?null:toolMode;
        if(!Set.of("ONLINE","ASSESSMENT").contains(selectedSource))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"来源无效");
        if("ASSESSMENT".equals(selectedSource)&&(selectedMode==null||!Set.of("LIVE","FIXTURE").contains(selectedMode)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"评测概览必须选择 LIVE 或 FIXTURE");
        if("ONLINE".equals(selectedSource)&&selectedMode!=null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"在线流量不适用工具模式");
        var result=new LinkedHashMap<String,Object>(jdbc.queryForMap("""
        WITH filtered AS (
          SELECT r.* FROM assessment_runs r
          LEFT JOIN assessment_samples sample ON sample.id=r.sample_id
          LEFT JOIN assessment_batches batch ON batch.id=sample.batch_id
          WHERE r.source=? AND (?::text IS NULL OR batch.tool_mode=?)
        ),
        model_metrics AS (SELECT s.* FROM assessment_spans s JOIN filtered r ON r.id=s.run_id WHERE s.kind='MODEL'),
        samples AS (SELECT s.* FROM assessment_samples s WHERE EXISTS(SELECT 1 FROM filtered r WHERE r.sample_id=s.id))
        SELECT count(*) AS sample_count,
        count(*) FILTER(WHERE status='PLANNING_COMPLETED' AND artifact_available) AS completed_plans,
        percentile_cont(0.5) WITHIN GROUP(ORDER BY duration_ms) FILTER(WHERE status='PLANNING_COMPLETED' AND artifact_available) AS p50_ms,
        percentile_cont(0.95) WITHIN GROUP(ORDER BY duration_ms) FILTER(WHERE status='PLANNING_COMPLETED' AND artifact_available) AS p95_ms,
        avg(duration_ms) FILTER(WHERE status='PLANNING_COMPLETED' AND artifact_available) AS mean_ms,
        stddev_pop(duration_ms) FILTER(WHERE status='PLANNING_COMPLETED' AND artifact_available) AS stddev_ms,
        count(*) FILTER(WHERE status IN ('FAILED','INTERRUPTED','INCOMPLETE','TIMED_OUT'))::float / nullif(count(*),0) AS failure_rate,
        count(*) FILTER(WHERE status='TIMED_OUT')::float/nullif(count(*),0) AS timeout_rate,
        avg(duration_ms) FILTER(WHERE status IN ('FAILED','TIMED_OUT','CANCELLED','INTERRUPTED')) AS failed_mean_ms,
        count(*) FILTER(WHERE data_complete)::float / nullif(count(*),0) AS data_completeness,
        count(*) FILTER(WHERE status IN ('PLANNING_COMPLETED','REPLIED','WAITING_INPUT'))::float/nullif(count(*),0) AS execution_success_rate,
        (SELECT sum(input_tokens) FROM model_metrics) AS known_input_tokens,
        (SELECT sum(output_tokens) FROM model_metrics) AS known_output_tokens,
        (SELECT sum(input_tokens) FROM model_metrics WHERE phase='PLANNING') AS planning_input_tokens,
        (SELECT sum(output_tokens) FROM model_metrics WHERE phase='PLANNING') AS planning_output_tokens,
        (SELECT sum(input_tokens) FROM model_metrics WHERE phase='SCORING') AS scoring_input_tokens,
        (SELECT sum(output_tokens) FROM model_metrics WHERE phase='SCORING') AS scoring_output_tokens,
        (SELECT sum(s.cost) FROM assessment_spans s JOIN filtered r ON r.id=s.run_id WHERE s.kind IN ('MODEL','TOOL')) AS known_cost,
        (SELECT sum(s.cost) FROM assessment_spans s JOIN filtered r ON r.id=s.run_id WHERE s.kind='TOOL') AS known_tool_cost,
        (SELECT count(*) FROM assessment_spans s JOIN filtered r ON r.id=s.run_id WHERE s.kind='TOOL') AS tool_calls,
        (SELECT sum(cost) FROM model_metrics WHERE phase='SCORING') AS scoring_known_cost,
        (SELECT sum(input_tokens+output_tokens) FROM model_metrics WHERE phase='SCORING') AS scoring_known_tokens,
        (SELECT sum(cost) FROM model_metrics WHERE phase='PLANNING') AS planning_known_cost,
        (SELECT bool_and(s.cost_complete) FROM assessment_spans s JOIN filtered r ON r.id=s.run_id WHERE s.kind IN ('MODEL','TOOL')) AS cost_complete,
        (SELECT count(*) FILTER(WHERE usage_kind='ACTUAL')::float/nullif(count(*),0) FROM model_metrics) AS usage_completeness,
        (SELECT count(*) FILTER(WHERE score_status='SUCCEEDED' AND (score_json::jsonb->>'passed')::boolean)::float/nullif(count(*),0) FROM samples) AS score_pass_rate,
        (SELECT count(*) FROM samples) AS assessment_sample_count,
        (SELECT count(*) FILTER(WHERE score_status='SUCCEEDED') FROM samples) AS scored_sample_count,
        (SELECT count(*) FILTER(WHERE score_status='ERROR') FROM samples) AS score_errors,
        (SELECT count(*) FILTER(WHERE rule_json IS NOT NULL AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(rule_json::jsonb) rule WHERE rule->>'status'<>'PASS'))::float/nullif(count(*),0) FROM samples) AS rule_pass_rate,
        (SELECT count(*) FILTER(WHERE status='SUCCEEDED' AND score_status='SUCCEEDED' AND (score_json::jsonb->>'passed')::boolean AND rule_json IS NOT NULL AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(rule_json::jsonb) rule WHERE rule->>'status'<>'PASS'))::float/nullif(count(*),0) FROM samples) AS overall_pass_rate,
        (SELECT count(*) FILTER(WHERE rule->>'status' IN ('PASS','FAIL'))::float/nullif(count(*),0) FROM samples CROSS JOIN LATERAL jsonb_array_elements(rule_json::jsonb) rule WHERE rule->>'method'='HARD_CONSTRAINT') AS hard_constraint_coverage
        FROM filtered
        """,selectedSource,selectedMode,selectedMode));
        result.put("source",selectedSource);result.put("tool_mode",selectedMode==null?"N/A":selectedMode);
        return result;
    }
}
