package com.travelscope.assessment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL percentiles and recursive aggregates in a disposable, owned schema. No model calls. */
@EnabledIfEnvironmentVariable(named="ASSESSMENT_TEST_DATABASE_URL",matches=".+")
class AssessmentStatisticsPostgresTest {
    @Test void metricsAreScopedDeduplicatedAndNullPreserving() throws Exception {
        Properties credentials=new Properties();
        for(String key:List.of("USER","PASSWORD")){String value=System.getenv("ASSESSMENT_TEST_DATABASE_"+key);if(value!=null)credentials.setProperty(key.toLowerCase(Locale.ROOT),value);}
        String schema="assessment_stats_"+UUID.randomUUID().toString().replace("-","");
        try(Connection connection=DriverManager.getConnection(System.getenv("ASSESSMENT_TEST_DATABASE_URL"),credentials)){
            boolean owned=false;
            try {
                connection.createStatement().execute("CREATE SCHEMA "+schema);owned=true;connection.setSchema(schema);
                connection.createStatement().execute("SET statement_timeout='5s'");
                JdbcTemplate jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
                String upgrade;try(var in=getClass().getResourceAsStream("/assessment-upgrade.sql")){upgrade=new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);}
                for(String statement:upgrade.replaceAll("(?m)^--.*$","").split(";"))if(!statement.isBlank())jdbc.execute(statement);
                var queries=new AssessmentQueries(jdbc);
                UUID original=batch(jdbc,"FIXTURE"),current=batch(jdbc,"FIXTURE"),live=batch(jdbc,"LIVE");
                UUID originalSample=sample(jdbc,original,"COMMON","SUCCEEDED"),currentSample=sample(jdbc,current,"COMMON","SUCCEEDED");
                UUID first=run(jdbc,originalSample,1000,"REPLIED"),last=run(jdbc,originalSample,2000,"PLANNING_COMPLETED");
                jdbc.update("UPDATE assessment_runs SET started_at=now()-interval '1 hour' WHERE id=?",first);
                UUID root=span(jdbc,last,null,"AGENT","planning-agent","PLANNING","SUCCEEDED",1300,null,null,null);
                UUID child=span(jdbc,last,root,"AGENT","poi-research","PLANNING","SUCCEEDED",1200,null,null,null);
                UUID logical=span(jdbc,last,child,"MODEL_CALL","model-a","PLANNING","SUCCEEDED",1100,999,999,"999");
                span(jdbc,last,logical,"MODEL","model-a","PLANNING","SUCCEEDED",100,10,5,".1");
                span(jdbc,last,logical,"MODEL","model-a","PLANNING","SUCCEEDED",900,20,10,".2");
                span(jdbc,last,logical,"MODEL","model-a","PLANNING","FAILED",300,5,null,null);
                span(jdbc,last,root,"TOOL","getWeather","PLANNING","SUCCESS",30,null,null,".01");
                span(jdbc,last,root,"MODEL","judge","SCORING","SUCCEEDED",300,7,3,".07");
                span(jdbc,first,null,"MODEL","earlier-model","PLANNING","SUCCEEDED",80,2,1,".02");
                UUID newRun=run(jdbc,currentSample,2500,"PLANNING_COMPLETED");
                span(jdbc,newRun,null,"MODEL","model-a","PLANNING","SUCCEEDED",500,50,25,".5");
                UUID extra=run(jdbc,sample(jdbc,original,"ORIGINAL_ONLY","ERROR"),100000,"PLANNING_COMPLETED");
                span(jdbc,extra,null,"MODEL","noise-model","PLANNING","SUCCEEDED",9999,10000,10000,"100");
                UUID real=run(jdbc,sample(jdbc,live,"COMMON","SUCCEEDED"),900000,"PLANNING_COMPLETED");
                span(jdbc,real,null,"MODEL","model-a","PLANNING","SUCCEEDED",900000,999999,999999,"999");

                var components=queries.components("ASSESSMENT","FIXTURE","PLANNING","MODEL",original);
                var model=components.stream().filter(row->"model-a".equals(row.get("component_name"))).findFirst().orElseThrow();
                assertEquals(3,((Number)model.get("call_count")).intValue());
                assertEquals(2,((Number)model.get("duration_samples")).intValue());
                assertEquals(1,((Number)model.get("failed")).intValue());
                assertEquals(500d,((Number)model.get("p50_ms")).doubleValue());
                assertEquals(860d,((Number)model.get("p95_ms")).doubleValue(),.001);
                assertEquals(300d,((Number)model.get("failed_mean_ms")).doubleValue());
                assertEquals(35L,((Number)model.get("input_tokens")).longValue());
                assertEquals(15L,((Number)model.get("output_tokens")).longValue());
                assertEquals(false,model.get("usage_complete"));assertEquals(false,model.get("cost_complete"));
                var agent=queries.components("ASSESSMENT","FIXTURE","PLANNING","AGENT",original).stream().filter(row->"planning-agent".equals(row.get("component_name"))).findFirst().orElseThrow();
                assertEquals(35L,((Number)agent.get("input_tokens")).longValue()); // excludes scoring phase and logical container
                assertEquals(0,new BigDecimal(".31").compareTo((BigDecimal)agent.get("known_cost")));
                var tool=queries.components("ASSESSMENT","FIXTURE","PLANNING","TOOL",original).getFirst();
                assertNull(tool.get("input_tokens"));assertNull(tool.get("output_tokens"));

                var compared=queries.comparisonMetrics(original,current);
                assertEquals(2,compared.size());
                var old=compared.stream().filter(row->original.equals(row.get("batch_id"))).findFirst().orElseThrow();
                assertEquals("COMMON",old.get("case_id"));assertEquals(2000d,((Number)old.get("p50_ms")).doubleValue());
                assertEquals(44L,((Number)old.get("known_input_tokens")).longValue());
                assertEquals(19L,((Number)old.get("known_output_tokens")).longValue());
                assertEquals(37L,((Number)old.get("planning_input_tokens")).longValue());
                assertEquals(7L,((Number)old.get("scoring_input_tokens")).longValue());
                assertEquals(0,new BigDecimal(".40").compareTo((BigDecimal)old.get("known_cost")));
                assertEquals(false,old.get("usage_complete"));
                var summary=queries.batchMetrics(original);
                assertEquals(2L,((Number)summary.get("samples")).longValue());
                assertEquals(1L,((Number)summary.get("score_count")).longValue());
                assertEquals(90d,((Number)summary.get("mean_score")).doubleValue()); // error is not a zero score
                assertEquals(51000d,((Number)summary.get("p50_ms")).doubleValue());
                assertTrue(queries.comparisonComponents(original,current).stream().noneMatch(row->"noise-model".equals(row.get("component_name"))));

                jdbc.update("INSERT INTO assessment_evaluations(id,run_id,status,result_json,scorer_version) VALUES (?,?,'ERROR','{\"errorType\":\"TestError\"}','test')",UUID.randomUUID(),last);
                var runs=queries.runs("ASSESSMENT",0,25,null,null,null,"FIXTURE","MODEL","model-a","PLANNING",original);
                assertEquals(1,runs.size());assertEquals("ERROR",runs.getFirst().get("latest_score_status"));
                assertEquals(1L,((Number)runs.getFirst().get("failed_span_count")).longValue());
                assertEquals(60L,((Number)runs.getFirst().get("tokens")).longValue()); // all phases of this round, no MODEL_CALL
                assertFalse(queries.spans(last).isEmpty());

                UUID cycleRun=run(jdbc,sample(jdbc,current,"CYCLE","SKIPPED"),1,"INCOMPLETE");
                UUID a=span(jdbc,cycleRun,null,"AGENT","cycle-a","PLANNING","SUCCEEDED",1,null,null,null);
                UUID b=span(jdbc,cycleRun,a,"AGENT","cycle-b","PLANNING","SUCCEEDED",1,null,null,null);
                jdbc.update("UPDATE assessment_spans SET parent_id=? WHERE id=?",b,a);
                span(jdbc,cycleRun,b,"MODEL","cycle-model","PLANNING","SUCCEEDED",1,11,4,null);
                var cycle=queries.spans(cycleRun);assertEquals(3,cycle.size());
                assertEquals(11L,((Number)cycle.stream().filter(row->a.equals(row.get("id"))).findFirst().orElseThrow().get("aggregate_input_tokens")).longValue());
                UUID missing=run(jdbc,sample(jdbc,current,"NO_USAGE","SKIPPED"),1,"INCOMPLETE");
                span(jdbc,missing,null,"MODEL","unknown-usage","PLANNING","FAILED",1,null,null,null);
                var unknown=queries.components("ASSESSMENT","FIXTURE","PLANNING","MODEL",current).stream().filter(row->"unknown-usage".equals(row.get("component_name"))).findFirst().orElseThrow();
                assertNull(unknown.get("input_tokens"));assertNull(unknown.get("p50_ms"));assertNull(unknown.get("known_cost"));
            } finally {
                if(owned){connection.setSchema("public");connection.createStatement().execute("DROP SCHEMA "+schema+" CASCADE");}
            }
        }
    }
    private UUID batch(JdbcTemplate jdbc,String mode){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO assessment_batches(id,request_key,status,tool_mode,repetitions,config_json) VALUES (?,?,'COMPLETED',?,1,'{}')",id,id.toString(),mode);return id;}
    private UUID sample(JdbcTemplate jdbc,UUID batch,String scenario,String scoreStatus){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO assessment_samples(id,batch_id,case_id,repetition,case_json,status,rule_json,score_status,score_json,session_id) VALUES (?,?,?,1,'{}','SUCCEEDED','[{\"status\":\"PASS\"}]',?,'{\"total\":90,\"passed\":true}',?)",id,batch,scenario,scoreStatus,"test-"+id);return id;}
    private UUID run(JdbcTemplate jdbc,UUID sample,int ms,String status){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO assessment_runs(id,turn_id,sample_id,source,status,duration_ms,ended_at,artifact_available) VALUES (?,?,?,'ASSESSMENT',?,?,now(),?)",id,UUID.randomUUID(),sample,status,ms,"PLANNING_COMPLETED".equals(status));return id;}
    private UUID span(JdbcTemplate jdbc,UUID run,UUID parent,String kind,String name,String phase,String status,int ms,Integer input,Integer output,String cost){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO assessment_spans(id,run_id,parent_id,kind,name,model,phase,status,duration_ms,started_at,ended_at,input_tokens,output_tokens,usage_kind,cost,cost_complete) VALUES (?,?,?,?,?,?,?,?,?,now(),now(),?,?,?,?,?)",id,run,parent,kind,name,kind.equals("MODEL")?name:null,phase,status,ms,input,output,input!=null&&output!=null?"ACTUAL":"PARTIAL",cost==null?null:new BigDecimal(cost),cost!=null);return id;}
}
