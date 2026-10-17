package com.travelscope.assessment;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Read-only aggregations. MODEL_CALL is a logical container, never a billable model attempt. */
final class AssessmentStatistics {
    private final JdbcTemplate jdbc;
    AssessmentStatistics(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    // The path guard also makes legacy malformed parent cycles safe to inspect.
    static final String ROLLUPS = """
        , tree AS (
          SELECT id AS ancestor,id AS descendant,ARRAY[id] AS path FROM nodes
          UNION ALL SELECT t.ancestor,n.id,t.path||n.id FROM tree t JOIN nodes n ON n.parent_id=t.descendant
          WHERE NOT n.id=ANY(t.path)
        ), rollup AS (
          SELECT t.ancestor,
            sum(n.input_tokens) FILTER(WHERE n.kind='MODEL') AS aggregate_input_tokens,
            sum(n.output_tokens) FILTER(WHERE n.kind='MODEL') AS aggregate_output_tokens,
            count(*) FILTER(WHERE n.kind='MODEL') AS aggregate_model_count,
            bool_and(n.usage_kind='ACTUAL' AND n.input_tokens IS NOT NULL AND n.output_tokens IS NOT NULL)
              FILTER(WHERE n.kind='MODEL') AS aggregate_usage_complete,
            sum(n.cost) FILTER(WHERE n.kind IN ('MODEL','TOOL')) AS aggregate_cost,
            bool_and(n.cost_complete AND n.cost IS NOT NULL) FILTER(WHERE n.kind IN ('MODEL','TOOL')) AS aggregate_cost_complete
          FROM tree t JOIN nodes n ON n.id=t.descendant JOIN nodes a ON a.id=t.ancestor AND n.phase=a.phase GROUP BY t.ancestor
        ), measured AS (
          SELECT n.*,u.aggregate_input_tokens,u.aggregate_output_tokens,u.aggregate_model_count,
            u.aggregate_usage_complete,u.aggregate_cost,u.aggregate_cost_complete
          FROM nodes n LEFT JOIN rollup u ON u.ancestor=n.id
        )
        """;

    static void scope(String source,String mode) {
        if(!Set.of("ONLINE","ASSESSMENT").contains(source))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"来源无效");
        if("ASSESSMENT".equals(source)&&(mode==null||!Set.of("LIVE","FIXTURE").contains(mode)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"评测统计必须选择工具模式");
        if("ONLINE".equals(source)&&mode!=null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"在线流量不适用工具模式");
    }

    List<Map<String,Object>> components(String source,String mode,String phase,String kind,UUID batchId) {
        scope(source,mode);
        if(phase!=null&&!Set.of("PLANNING","SCORING").contains(phase))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"阶段无效");
        if(kind!=null&&!Set.of("AGENT","MODEL","TOOL").contains(kind))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"组件类型无效");
        if(batchId!=null&&!"ASSESSMENT".equals(source))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"批次仅适用于测试运行");
        List<Object> args=new ArrayList<>(Arrays.asList(source,mode,mode));
        String filter="r.source=? AND (?::text IS NULL OR b.tool_mode=?)";
        if(batchId!=null){filter+=" AND b.id=?";args.add(batchId);}
        String where=" WHERE kind IN ('AGENT','MODEL','TOOL')";
        if(phase!=null){where+=" AND phase=?";args.add(phase);}
        if(kind!=null){where+=" AND kind=?";args.add(kind);}
        return jdbc.queryForList(nodes(filter)+ROLLUPS+componentSelect(false)+where+componentGroup(false),args.toArray());
    }

    List<Map<String,Object>> comparisonComponents(UUID baseline,UUID candidate) {
        String filter="b.id IN (?,?) AND sample.case_id IN (SELECT case_id FROM assessment_samples WHERE batch_id=? INTERSECT SELECT case_id FROM assessment_samples WHERE batch_id=?)";
        return jdbc.queryForList(nodes(filter)+ROLLUPS+componentSelect(true)+" WHERE kind IN ('AGENT','MODEL','TOOL')"+componentGroup(true),baseline,candidate,baseline,candidate);
    }

    private String nodes(String filter) {
        return "WITH RECURSIVE nodes AS (SELECT s.*,r.data_complete,sample.batch_id,sample.case_id FROM assessment_spans s JOIN assessment_runs r ON r.id=s.run_id LEFT JOIN assessment_samples sample ON sample.id=r.sample_id LEFT JOIN assessment_batches b ON b.id=sample.batch_id WHERE "+filter+")";
    }
    private String componentSelect(boolean comparison) {
        return "SELECT "+(comparison?"batch_id,case_id,":"")+"""
          kind,CASE WHEN kind='MODEL' THEN coalesce(nullif(model,''),name) ELSE name END AS component_name,phase,
          count(*) AS call_count,
          count(*) FILTER(WHERE status IN ('SUCCESS','SUCCEEDED')) AS succeeded,
          count(*) FILTER(WHERE status IN ('FAILED','ERROR','TIMED_OUT','INTERRUPTED')) AS failed,
          count(*) FILTER(WHERE status='CANCELLED') AS cancelled,
          count(*) FILTER(WHERE status IN ('SUCCESS','SUCCEEDED') AND duration_ms>=0 AND ended_at IS NOT NULL) AS duration_samples,
          avg(duration_ms) FILTER(WHERE status IN ('SUCCESS','SUCCEEDED') AND duration_ms>=0 AND ended_at IS NOT NULL) AS mean_ms,
          percentile_cont(0.5) WITHIN GROUP(ORDER BY duration_ms) FILTER(WHERE status IN ('SUCCESS','SUCCEEDED') AND duration_ms>=0 AND ended_at IS NOT NULL) AS p50_ms,
          percentile_cont(0.95) WITHIN GROUP(ORDER BY duration_ms) FILTER(WHERE status IN ('SUCCESS','SUCCEEDED') AND duration_ms>=0 AND ended_at IS NOT NULL) AS p95_ms,
          avg(duration_ms) FILTER(WHERE status IN ('FAILED','ERROR','TIMED_OUT','INTERRUPTED') AND ended_at IS NOT NULL) AS failed_mean_ms,
          sum(CASE WHEN kind='AGENT' THEN aggregate_input_tokens WHEN kind='MODEL' THEN input_tokens END) AS input_tokens,
          sum(CASE WHEN kind='AGENT' THEN aggregate_output_tokens WHEN kind='MODEL' THEN output_tokens END) AS output_tokens,
          bool_and(CASE WHEN kind='AGENT' THEN aggregate_usage_complete WHEN kind='MODEL' THEN usage_kind='ACTUAL' AND input_tokens IS NOT NULL AND output_tokens IS NOT NULL END) AS usage_complete,
          sum(CASE WHEN kind='AGENT' THEN aggregate_cost ELSE cost END) AS known_cost,
          bool_and(CASE WHEN kind='AGENT' THEN aggregate_cost_complete ELSE cost_complete AND cost IS NOT NULL END) AS cost_complete,
          bool_and(data_complete) AS data_complete
          FROM measured
          """;
    }
    private String componentGroup(boolean comparison) {
        return " GROUP BY "+(comparison?"batch_id,case_id,":"")+"kind,component_name,phase ORDER BY "+(comparison?"case_id,batch_id,":"")+"kind,component_name,phase";
    }

    List<Map<String,Object>> sampleMetrics(UUID baseline,UUID candidate,boolean byScenario) {
        List<Object> args=new ArrayList<>();args.add(baseline);
        String chosen="batch_id=?";
        if(candidate!=null){chosen="batch_id IN (?,?) AND case_id IN (SELECT case_id FROM assessment_samples WHERE batch_id=? INTERSECT SELECT case_id FROM assessment_samples WHERE batch_id=?)";args.add(candidate);args.add(baseline);args.add(candidate);}
        String query="WITH chosen AS (SELECT * FROM assessment_samples WHERE "+chosen+")"+"""
          , sample_runs AS (
            SELECT r.sample_id,sum(r.duration_ms) AS processing_ms,bool_and(r.data_complete) AS data_complete
            FROM assessment_runs r JOIN chosen s ON s.id=r.sample_id GROUP BY r.sample_id
          ), sample_usage AS (
            SELECT r.sample_id,
              sum(p.input_tokens) FILTER(WHERE p.kind='MODEL') AS known_input_tokens,
              sum(p.output_tokens) FILTER(WHERE p.kind='MODEL') AS known_output_tokens,
              sum(p.input_tokens) FILTER(WHERE p.kind='MODEL' AND p.phase='PLANNING') AS planning_input_tokens,
              sum(p.output_tokens) FILTER(WHERE p.kind='MODEL' AND p.phase='PLANNING') AS planning_output_tokens,
              sum(p.input_tokens) FILTER(WHERE p.kind='MODEL' AND p.phase='SCORING') AS scoring_input_tokens,
              sum(p.output_tokens) FILTER(WHERE p.kind='MODEL' AND p.phase='SCORING') AS scoring_output_tokens,
              bool_and(p.usage_kind='ACTUAL' AND p.input_tokens IS NOT NULL AND p.output_tokens IS NOT NULL) FILTER(WHERE p.kind='MODEL') AS usage_complete,
              sum(p.cost) FILTER(WHERE p.kind IN ('MODEL','TOOL')) AS known_cost,
              bool_and(p.cost_complete AND p.cost IS NOT NULL) FILTER(WHERE p.kind IN ('MODEL','TOOL')) AS cost_complete
            FROM assessment_runs r JOIN chosen s ON s.id=r.sample_id JOIN assessment_spans p ON p.run_id=r.id GROUP BY r.sample_id
          ), per_sample AS (
            SELECT s.*,r.duration_ms AS final_round_ms,r.status AS final_status,r.artifact_available,
              all_runs.processing_ms,all_runs.data_complete,u.known_input_tokens,u.known_output_tokens,
              u.planning_input_tokens,u.planning_output_tokens,u.scoring_input_tokens,u.scoring_output_tokens,
              u.usage_complete,u.known_cost,u.cost_complete
            FROM chosen s LEFT JOIN sample_runs all_runs ON all_runs.sample_id=s.id
            LEFT JOIN sample_usage u ON u.sample_id=s.id
            LEFT JOIN LATERAL (SELECT * FROM assessment_runs WHERE sample_id=s.id ORDER BY started_at DESC,id DESC LIMIT 1) r ON true
          ) SELECT batch_id,
          """+(byScenario?"case_id,":"")+"""
            count(*) AS samples,
            count(*) FILTER(WHERE status='SUCCEEDED')::float/nullif(count(*),0) AS execution_success_rate,
            count(*) FILTER(WHERE score_status='ERROR') AS score_errors,
            count(*) FILTER(WHERE score_status='SUCCEEDED') AS score_count,
            avg((score_json::jsonb->>'total')::float) FILTER(WHERE score_status='SUCCEEDED') AS mean_score,
            count(*) FILTER(WHERE final_status='TIMED_OUT')::float/nullif(count(*),0) AS timeout_rate,
            count(*) FILTER(WHERE final_status='PLANNING_COMPLETED' AND artifact_available) AS completed_plans,
            percentile_cont(0.5) WITHIN GROUP(ORDER BY final_round_ms) FILTER(WHERE final_status='PLANNING_COMPLETED' AND artifact_available) AS p50_ms,
            percentile_cont(0.95) WITHIN GROUP(ORDER BY final_round_ms) FILTER(WHERE final_status='PLANNING_COMPLETED' AND artifact_available) AS p95_ms,
            avg(final_round_ms) FILTER(WHERE final_status='PLANNING_COMPLETED' AND artifact_available) AS mean_ms,
            stddev_pop(final_round_ms) FILTER(WHERE final_status='PLANNING_COMPLETED' AND artifact_available) AS stddev_ms,
            avg(final_round_ms) FILTER(WHERE final_status IN ('FAILED','CANCELLED','INTERRUPTED','TIMED_OUT')) AS failed_mean_ms,
            sum(processing_ms) AS system_time_ms,
            sum(known_input_tokens) AS known_input_tokens,sum(known_output_tokens) AS known_output_tokens,
            sum(planning_input_tokens) AS planning_input_tokens,sum(planning_output_tokens) AS planning_output_tokens,
            sum(scoring_input_tokens) AS scoring_input_tokens,sum(scoring_output_tokens) AS scoring_output_tokens,
            bool_and(coalesce(usage_complete,false)) AS usage_complete,
            sum(known_cost) AS known_cost,bool_and(coalesce(cost_complete,false)) AS cost_complete,
            bool_and(coalesce(data_complete,false)) AS data_complete,
            count(*) FILTER(WHERE score_status='SUCCEEDED' AND (score_json::jsonb->>'passed')::boolean)::float/nullif(count(*),0) AS score_pass_rate,
            count(*) FILTER(WHERE rule_json IS NOT NULL AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(rule_json::jsonb) rule WHERE rule->>'status'<>'PASS'))::float/nullif(count(*),0) AS rule_pass_rate,
            count(*) FILTER(WHERE status='SUCCEEDED' AND score_status='SUCCEEDED' AND (score_json::jsonb->>'passed')::boolean AND rule_json IS NOT NULL AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(rule_json::jsonb) rule WHERE rule->>'status'<>'PASS'))::float/nullif(count(*),0) AS overall_pass_rate
          FROM per_sample GROUP BY batch_id
          """+(byScenario?",case_id ORDER BY case_id,batch_id":"");
        return jdbc.queryForList(query,args.toArray());
    }
}
