package com.travelscope.assessment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelscope.llm.*;
import io.agentscope.core.model.*;
import io.agentscope.core.message.Msg;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.sdk.trace.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="ASSESSMENT_TEST_DATABASE_URL",matches=".+")
class AssessmentTracePostgresTest {
    @Test void incrementalUpgradeRepairsInstalledSchemaWithoutReinitializingIt() throws Exception {
        try(var db=new Database()) {
            UUID historical=UUID.randomUUID();
            db.jdbc.update("INSERT INTO assessment_runs(id,turn_id,source,status,duration_ms) VALUES (?,?,'ONLINE','REPLIED',123)",historical,UUID.randomUUID());
            db.jdbc.execute("ALTER TABLE assessment_spans DROP COLUMN ttft_ms");
            String incremental=new String(Objects.requireNonNull(getClass().getResourceAsStream("/assessment-trace-upgrade.sql")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            for(int repeat=0;repeat<2;repeat++)for(String statement:incremental.replaceAll("(?m)^--.*$","").split(";"))if(!statement.isBlank())db.jdbc.execute(statement);
            var run=db.store.begin("incremental-upgrade",null);
            var model=db.store.span(run,"MODEL","stub",null,null);
            model.ttft(12.5);model.usage("stub",new ChatUsage(10,3,0d),null);model.end("SUCCEEDED",null,null);
            db.store.finish(run,"REPLIED",0,false);db.store.flush(run);
            var stored=db.jdbc.queryForMap("SELECT status,input_tokens,output_tokens,ttft_ms FROM assessment_spans WHERE id=?",model.id);
            assertEquals("SUCCEEDED",stored.get("status"));assertEquals(10L,stored.get("input_tokens"));assertEquals(3L,stored.get("output_tokens"));assertEquals(12.5,((Number)stored.get("ttft_ms")).doubleValue());
            assertEquals(123d,db.jdbc.queryForObject("SELECT duration_ms FROM assessment_runs WHERE id=?",Double.class,historical));
            assertEquals(false,db.store.health().get("degraded"));
        }
    }
    static class Database implements AutoCloseable {
        final Connection connection;
        final String schema="trace_it_"+UUID.randomUUID().toString().replace("-","");
        final JdbcTemplate jdbc;
        final AssessmentProperties properties=new AssessmentProperties();
        final ObservationStore store;
        Database() throws Exception {
            Properties credentials=new Properties();
            for(String key:List.of("USER","PASSWORD")){
                String value=System.getenv("ASSESSMENT_TEST_DATABASE_"+key);
                if(value!=null)credentials.setProperty(key.toLowerCase(Locale.ROOT),value);
            }
            String url=System.getenv("ASSESSMENT_TEST_DATABASE_URL");
            if(!url.contains("test"))throw new IllegalArgumentException("An explicitly named test database is required");
            connection=DriverManager.getConnection(url,credentials);
            connection.createStatement().execute("CREATE SCHEMA "+schema);connection.setSchema(schema);
            jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            String upgrade=new String(Objects.requireNonNull(getClass().getResourceAsStream("/assessment-upgrade.sql")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            for(int repeat=0;repeat<2;repeat++)for(String statement:upgrade.replaceAll("(?m)^--.*$","").split(";"))if(!statement.isBlank())jdbc.execute(statement);
            properties.setEnabled(true);
            var price=new AssessmentProperties.Price();price.setInputPerMillion(BigDecimal.ONE);price.setOutputPerMillion(new BigDecimal("2"));properties.getPrices().put("stub",price);
            store=new ObservationStore(jdbc,properties,new PayloadRedactor(new ObjectMapper()));
        }
        public void close() throws Exception {
            store.close();connection.setSchema("public");connection.createStatement().execute("DROP SCHEMA "+schema+" CASCADE");connection.close();
        }
    }

    @Test void realProviderFailoverProducesTwoActualSpansAndOneBusinessRun() throws Exception {
        try(var db=new Database()) {
            var calls=new AtomicInteger();
            Model failed=new Model(){public String getModelName(){return "stub";}public Flux<ChatResponse> stream(List<Msg> m,List<ToolSchema> t,GenerateOptions o){calls.incrementAndGet();return Flux.error(new IllegalStateException("synthetic provider failure"));}};
            Model succeeded=new Model(){public String getModelName(){return "stub";}public Flux<ChatResponse> stream(List<Msg> m,List<ToolSchema> t,GenerateOptions o){calls.incrementAndGet();return Flux.just(ChatResponse.builder().usage(new ChatUsage(10,3,0d)).build());}};
            var providers=new ArrayList<ModelProviderPool.Provider>();
            int priority=0;
            for(Model raw:List.of(failed,succeeded))providers.add(new ModelProviderPool.Provider("p"+priority,priority++,new ObservedModel(raw,db.store),new ProviderCircuitBreaker("synthetic",10,.5,1000),Duration.ofSeconds(2),Duration.ofSeconds(2)));
            var pool=new ModelProviderPool("test",providers);
            var run=db.store.begin("controlled-online",null);var agent=db.store.span(run,"AGENT","planning-agent",null,"hello");
            var logical=db.store.span(agent.child(),"MODEL_CALL","planning-agent",null,null);var attempts=new AttemptContext();
            pool.stream(List.of(),List.of(),null)
                .contextWrite(c->c.put(ObservationContext.KEY,logical.child()).put(AttemptContext.KEY,attempts))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).blockLast();
            logical.end("SUCCEEDED",null,null);agent.end("SUCCEEDED",null,null);db.store.finish(run,"REPLIED",999999,false);db.store.flush();
            assertEquals(2,calls.get());
            assertEquals(1,db.jdbc.queryForObject("SELECT count(*) FROM assessment_runs",Integer.class));
            var models=db.jdbc.queryForList("SELECT * FROM assessment_spans WHERE kind='MODEL' ORDER BY attempt_index");
            assertEquals(2,models.size());assertEquals(1,models.getFirst().get("attempt_index"));assertEquals(2,models.getLast().get("attempt_index"));
            assertEquals("FAILED",models.getFirst().get("status"));assertEquals("SUCCEEDED",models.getLast().get("status"));
            assertEquals(models.getFirst().get("attempt_group"),models.getLast().get("attempt_group"));
            assertEquals(10L,models.getLast().get("input_tokens"));assertNull(models.getFirst().get("input_tokens"));
            assertEquals(logical.id,models.getFirst().get("parent_id"));
            assertEquals(run.traceId(),models.getFirst().get("trace_id"));
            String nativeLogical=db.jdbc.queryForObject("SELECT otel_span_id FROM assessment_spans WHERE id=?",String.class,logical.id);
            assertEquals(nativeLogical,models.getLast().get("otel_parent_span_id"));
            var queries=new AssessmentQueries(db.jdbc);var overview=queries.overview("ONLINE");
            assertEquals(10L,((Number)overview.get("known_input_tokens")).longValue());assertEquals(3L,((Number)overview.get("known_output_tokens")).longValue());
            assertEquals(0,new BigDecimal("0.000016").compareTo((BigDecimal)overview.get("known_cost")));
            var component=queries.components("ONLINE",null,"PLANNING","MODEL",null).getFirst();
            assertEquals(2L,component.get("call_count"));assertEquals(1L,component.get("failed"));assertEquals(1L,component.get("succeeded"));
            assertEquals(4,queries.spans(run.runId()).size());
            assertTrue(((Number)queries.run(run.runId()).get("duration_ms")).doubleValue()<999999);
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new AssessmentController(queries,mock(AssessmentBatches.class),mock(AssessmentActions.class))).build();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin/assessment/overview").requestAttr("userRole","admin"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.sample_count").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.known_input_tokens").value(10));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin/assessment/runs/"+run.runId()+"/spans").requestAttr("userRole","admin"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(4));
        }
    }

    @Test void endedBeforeStartedAndDuplicateDeliveryPreserveFinalStateAndNativeIdentity() throws Exception {
        try(var db=new Database();var provider=SdkTracerProvider.builder().build()) {
            UUID run=UUID.randomUUID(),turn=UUID.randomUUID(),node=UUID.randomUUID();
            var tracer=provider.get("synthetic-replay");
            Span root=tracer.spanBuilder("root").setNoParent().setStartTimestamp(1,java.util.concurrent.TimeUnit.SECONDS)
                .setAttribute(AssessmentTraceProcessor.key("run_id"),run.toString()).setAttribute(AssessmentTraceProcessor.key("kind"),"RUN")
                .setAttribute(AssessmentTraceProcessor.key("turn_id"),turn.toString()).setAttribute(AssessmentTraceProcessor.key("source"),"ONLINE")
                .setAttribute(AssessmentTraceProcessor.key("session_id"),"out-of-order").setAttribute(AssessmentTraceProcessor.key("status"),"REPLIED").startSpan();
            Span child=tracer.spanBuilder("stub").setParent(io.opentelemetry.context.Context.root().with(root))
                .setStartTimestamp(1200,java.util.concurrent.TimeUnit.MILLISECONDS)
                .setAttribute(AssessmentTraceProcessor.key("run_id"),run.toString()).setAttribute(AssessmentTraceProcessor.key("kind"),"MODEL")
                .setAttribute(AssessmentTraceProcessor.key("node_id"),node.toString()).setAttribute(AssessmentTraceProcessor.key("turn_id"),turn.toString())
                .setAttribute(AssessmentTraceProcessor.key("phase"),"PLANNING").setAttribute(AssessmentTraceProcessor.key("status"),"SUCCEEDED").startSpan();
            child.end(1700,java.util.concurrent.TimeUnit.MILLISECONDS);root.end(3,java.util.concurrent.TimeUnit.SECONDS);
            var processor=new AssessmentTraceProcessor(db.jdbc,db.properties);
            try {
                processor.onEnd((ReadableSpan)child);processor.flush();
                assertFalse(db.jdbc.queryForObject("SELECT data_complete FROM assessment_runs WHERE id=?",Boolean.class,run));
                processor.onEnd((ReadableSpan)root);processor.onStart(io.opentelemetry.context.Context.root(),(ReadWriteSpan)child);
                processor.onStart(io.opentelemetry.context.Context.root(),(ReadWriteSpan)root);processor.onEnd((ReadableSpan)child);processor.onEnd((ReadableSpan)root);processor.flush();
                assertEquals(1,db.jdbc.queryForObject("SELECT count(*) FROM assessment_spans",Integer.class));
                assertEquals("SUCCEEDED",db.jdbc.queryForObject("SELECT status FROM assessment_spans WHERE id=?",String.class,node));
                assertEquals(500d,db.jdbc.queryForObject("SELECT duration_ms FROM assessment_spans WHERE id=?",Double.class,node));
                assertEquals("REPLIED",db.jdbc.queryForObject("SELECT status FROM assessment_runs WHERE id=?",String.class,run));
                assertEquals(2000d,db.jdbc.queryForObject("SELECT duration_ms FROM assessment_runs WHERE id=?",Double.class,run));
                assertTrue(db.jdbc.queryForObject("SELECT data_complete FROM assessment_runs WHERE id=?",Boolean.class,run));
                assertEquals(root.getSpanContext().getSpanId(),db.jdbc.queryForObject("SELECT root_span_id FROM assessment_runs WHERE id=?",String.class,run));
            } finally{processor.close();}
        }
    }

    @Test void laterManualScoringRestoresNativeParentWithoutChangingBusinessTiming() throws Exception {
        try(var db=new Database()) {
            var run=db.store.begin("manual-score",null);db.store.finish(run,"REPLIED",0,false);db.store.flush();
            Double original=db.jdbc.queryForObject("SELECT duration_ms FROM assessment_runs WHERE id=?",Double.class,run.runId());
            var later=new ObservationStore(db.jdbc,db.properties,new PayloadRedactor(new ObjectMapper()));
            try {
                var historical=new ObservationContext(run.runId(),run.turnId(),run.traceId(),null,null).withPurpose("SCORING");
                var score=later.span(historical,"AGENT","posthoc-scorer",null,"synthetic requirement");
                var model=later.span(score.child(),"MODEL","stub",null,null);model.usage("stub",new ChatUsage(4,2,0d),null);
                model.end("SUCCEEDED",null,null);score.end("SUCCEEDED",null,null);later.flush();
                assertEquals(run.traceId(),db.jdbc.queryForObject("SELECT trace_id FROM assessment_spans WHERE id=?",String.class,score.id));
                assertEquals(run.spanContext().getSpanId(),db.jdbc.queryForObject("SELECT otel_parent_span_id FROM assessment_spans WHERE id=?",String.class,score.id));
                var overview=new AssessmentQueries(db.jdbc).overview("ONLINE");assertEquals(6L,((Number)overview.get("scoring_known_tokens")).longValue());
                assertEquals(1L,overview.get("sample_count"));assertEquals(original,db.jdbc.queryForObject("SELECT duration_ms FROM assessment_runs WHERE id=?",Double.class,run.runId()));
            }finally{later.close();}
        }
    }

    @Test void restartMarksUnfinishedNodesAndNeverInvokesChat() throws Exception {
        try(var db=new Database()) {
            var run=db.store.begin("restart",null);db.store.span(run,"MODEL","stub",null,null);db.store.flush();
            var chat=mock(com.travelscope.service.ChatService.class);
            var batches=new AssessmentBatches(db.jdbc,new ObjectMapper(),db.properties,chat,db.store,
                mock(com.travelscope.collab.TaskWorkspace.class),mock(PosthocScorer.class),new AssessmentQueries(db.jdbc),new com.travelscope.config.AppProperties());
            try{batches.recover();verifyNoInteractions(chat);}finally{batches.close();}
            assertEquals("INTERRUPTED",db.jdbc.queryForObject("SELECT status FROM assessment_runs WHERE id=?",String.class,run.runId()));
            assertEquals("INTERRUPTED",db.jdbc.queryForObject("SELECT status FROM assessment_spans WHERE run_id=?",String.class,run.runId()));
            db.store.finish(run,"REPLIED",0,false);db.store.flush();
            assertEquals("INTERRUPTED",db.jdbc.queryForObject("SELECT status FROM assessment_runs WHERE id=?",String.class,run.runId()));
        }
    }

    @Test void legacyRecordsKeepTheirIdentityWhileTraceScopesStaySeparated() throws Exception {
        try(var db=new Database()) {
            UUID legacy=UUID.randomUUID();
            db.jdbc.update("INSERT INTO assessment_runs(id,turn_id,trace_id,source,status,duration_ms) VALUES (?,?,'legacy-trace','ONLINE','REPLIED',123)",legacy,UUID.randomUUID());
            assertEquals("LEGACY",db.jdbc.queryForObject("SELECT collection_version FROM assessment_runs WHERE id=?",String.class,legacy));
            assertNull(db.jdbc.queryForMap("SELECT root_span_id FROM assessment_runs WHERE id=?",legacy).get("root_span_id"));
            for(String mode:List.of("LIVE","FIXTURE")) {
                UUID batch=UUID.randomUUID(),sample=UUID.randomUUID();
                db.jdbc.update("INSERT INTO assessment_batches(id,request_key,status,tool_mode,repetitions,config_json) VALUES (?,?,'COMPLETED',?,1,'{}')",batch,batch.toString(),mode);
                db.jdbc.update("INSERT INTO assessment_samples(id,batch_id,case_id,repetition,case_json,status,session_id) VALUES (?,?,'trace-scope',1,'{}','SUCCEEDED','scope')",sample,batch);
                var run=db.store.beginAssessment("scope",sample,mode,batch);
                db.store.task(run,"CHAT");db.store.queue(run,12);db.store.flush();
                assertEquals("CHAT",db.jdbc.queryForObject("SELECT task_type FROM assessment_runs WHERE id=?",String.class,run.runId()));
                var call=db.store.span(run,"MODEL","stub",null,null);call.usage("stub",new ChatUsage(mode.equals("LIVE")?10:2,1,0d),null);
                call.end("SUCCEEDED",null,null);db.store.finish(run,"REPLIED",0,false);db.store.flush();
                assertEquals(mode,db.jdbc.queryForObject("SELECT trace_attributes->>'travelscope.tool_mode' FROM assessment_runs WHERE id=?",String.class,run.runId()));
            }
            var queries=new AssessmentQueries(db.jdbc);
            assertEquals(10,((Number)queries.overview("ASSESSMENT","LIVE").get("known_input_tokens")).intValue());
            assertEquals(2,((Number)queries.overview("ASSESSMENT","FIXTURE").get("known_input_tokens")).intValue());
            assertNull(queries.overview("ONLINE").get("known_input_tokens"));
            assertEquals(1L,queries.overview("ONLINE").get("sample_count"));
            assertEquals("legacy-trace",queries.run(legacy).get("trace_id"));
            assertEquals(123d,((Number)queries.run(legacy).get("duration_ms")).doubleValue());
        }
    }
}
