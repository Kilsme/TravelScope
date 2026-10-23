package com.travelscope.assessment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.sql.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** Explicit opt-in, isolated owned schema. Never uses the application's production connection. */
@EnabledIfEnvironmentVariable(named="ASSESSMENT_TEST_DATABASE_URL",matches=".+")
class AssessmentPostgresTest {
    @Test void upgradeQueriesAndRetention() throws Exception {
        Properties credentials=new Properties();
        for(String key:List.of("USER","PASSWORD")){String value=System.getenv("ASSESSMENT_TEST_DATABASE_"+key);if(value!=null)credentials.setProperty(key.toLowerCase(Locale.ROOT),value);}
        String schema="assessment_it_"+UUID.randomUUID().toString().replace("-","");boolean owned=false;
        try(Connection connection=DriverManager.getConnection(System.getenv("ASSESSMENT_TEST_DATABASE_URL"),credentials)){
            try {
                connection.createStatement().execute("CREATE SCHEMA "+schema);owned=true;connection.setSchema(schema);
                JdbcTemplate jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
                String sql;try(var in=getClass().getResourceAsStream("/assessment-upgrade.sql")){sql=new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);}
                for(int repeat=0;repeat<2;repeat++)for(String statement:sql.replaceAll("(?m)^--.*$", "").split(";"))if(!statement.isBlank())jdbc.execute(statement);
                var properties=new AssessmentProperties();properties.setEnabled(true);
                var price=new AssessmentProperties.Price();price.setInputPerMillion(BigDecimal.ONE);price.setOutputPerMillion(new BigDecimal("2"));properties.getPrices().put("stub",price);
                var store=new ObservationStore(jdbc,properties,new PayloadRedactor(new ObjectMapper()));
                try {
                    var run=store.begin("postgres-test",null);var planner=store.span(run,"AGENT","planning-agent",null,"test");planner.end("SUCCEEDED",null,null);var span=store.span(run,"MODEL","stub",null,"password=synthetic-secret");
                    span.usage("stub",new io.agentscope.core.model.ChatUsage(50,20,0d),null);span.end("SUCCEEDED","output",null);
                    store.recordArtifactMutation(run,span.id,"tasks/postgres-test/itinerary_draft.md","rejected draft");
                    store.recordArtifactMutation(run,span.id,"tasks/postgres-test/itinerary_draft.md","test plan");
                    store.recordArtifactMutation(run,span.id,"tasks/postgres-test/itinerary_draft.md","test plan");
                    store.finish(run,"PLANNING_COMPLETED",10,true);store.flush();
                    var queries=new AssessmentQueries(jdbc);assertEquals(1L,((Number)queries.overview(null).get("sample_count")).longValue());
                    // Equal timestamps and identical contents cannot identify which edit was final.
                    jdbc.update("UPDATE assessment_payloads SET created_at=now() WHERE run_id=? AND kind='ARTIFACT'",run.runId());
                    var finalArtifact=queries.finalPlanningArtifact(run.runId()).orElseThrow();
                    assertEquals("test plan",finalArtifact.get("body"));
                    assertEquals(jdbc.queryForObject("SELECT planning_artifact_id FROM assessment_runs WHERE id=?",UUID.class,run.runId()),finalArtifact.get("id"));
                    assertTrue(((Number)queries.overview(null).get("p50_ms")).doubleValue()>0);
                    assertFalse(queries.payload(span.id).toString().contains("synthetic-secret"));
                    assertEquals(1,queries.runs(null,0,25).size());assertEquals(2,queries.spans(run.runId()).size());
                    jdbc.update("UPDATE assessment_payloads SET created_at=now()-interval '31 days'");
                    assertTrue(queries.finalPlanningArtifact(run.runId()).isEmpty());
                    assertTrue(queries.payload(span.id).isEmpty());store.retain();
                    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM assessment_payloads",Integer.class));
                    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM assessment_runs",Integer.class));
                    jdbc.update("UPDATE assessment_runs SET started_at=now()-interval '181 days'");store.retain();
                    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM assessment_runs",Integer.class));
                    seedMode(jdbc,store,"LIVE",1000,1000,100);
                    seedMode(jdbc,store,"FIXTURE",10,10,5);
                    seedMode(jdbc,store,null,20,7,3);
                    var live=queries.overview("ASSESSMENT","LIVE");var fixture=queries.overview("ASSESSMENT","FIXTURE");var online=queries.overview(null);
                    assertEquals(1L,((Number)live.get("sample_count")).longValue());assertEquals(1L,((Number)fixture.get("sample_count")).longValue());assertEquals(1L,((Number)online.get("sample_count")).longValue());
                    assertEquals(1000d,((Number)live.get("p50_ms")).doubleValue());assertEquals(10d,((Number)fixture.get("p95_ms")).doubleValue());assertEquals(20d,((Number)online.get("p50_ms")).doubleValue());
                    assertEquals(1000L,((Number)live.get("known_input_tokens")).longValue());assertEquals(10L,((Number)fixture.get("known_input_tokens")).longValue());assertEquals(7L,((Number)online.get("known_input_tokens")).longValue());
                    assertEquals(0,new BigDecimal("0.0012").compareTo((BigDecimal)live.get("known_cost")));assertEquals(0,new BigDecimal("0.00002").compareTo((BigDecimal)fixture.get("known_cost")));
                    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->queries.overview("ASSESSMENT"));
                } finally {store.close();}
            } finally {
                if(owned){connection.setSchema("public");connection.createStatement().execute("DROP SCHEMA "+schema+" CASCADE");}
            }
        }
    }
    /** All three sources coexist in the same real PostgreSQL tables; each overview must remain isolated. */
    private void seedMode(JdbcTemplate jdbc,ObservationStore store,String mode,double ms,int input,int output) throws Exception {
        UUID sample=null;
        if(mode!=null){
            UUID batch=UUID.randomUUID();sample=UUID.randomUUID();
            jdbc.update("INSERT INTO assessment_batches(id,request_key,status,tool_mode,repetitions,config_json) VALUES (?,?,'COMPLETED',?,1,'{}')",batch,batch.toString(),mode);
            jdbc.update("INSERT INTO assessment_samples(id,batch_id,case_id,repetition,case_json,status,session_id,score_status) VALUES (?,?,'mode-test',1,'{}','SUCCEEDED',?,'SKIPPED')",sample,batch,"mode-"+sample);
        }
        String session="mode-"+UUID.randomUUID();var run=store.begin(session,sample);
        var planner=store.span(run,"AGENT","planning-agent",null,"test");planner.end("SUCCEEDED",null,null);
        var model=store.span(run,"MODEL","stub",null,"test");model.usage("stub",new io.agentscope.core.model.ChatUsage(input,output,0d),null);model.end("SUCCEEDED","test",null);
        var mutation=store.span(run,"TOOL","write_file",UUID.randomUUID().toString(),"test");mutation.end("SUCCESS","Written to tasks/"+session+"/itinerary_draft.md",null);
        store.recordArtifactMutation(run,mutation.id,"tasks/"+session+"/itinerary_draft.md","synthetic plan");
        store.finish(run,"PLANNING_COMPLETED",ms,true);store.flush();
        // Fixed fixture for aggregation tests; runtime duration comes from the SDK.
        jdbc.update("UPDATE assessment_runs SET duration_ms=? WHERE id=?",ms,run.runId());
    }

}
