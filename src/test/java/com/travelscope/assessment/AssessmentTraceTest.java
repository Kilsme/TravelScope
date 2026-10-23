package com.travelscope.assessment;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.ChatUsage;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.*;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssessmentTraceTest {
    static class CapturingExporter implements SpanExporter {
        final List<SpanData> spans=new CopyOnWriteArrayList<>();
        public CompletableResultCode export(Collection<SpanData> batch){spans.addAll(batch);return CompletableResultCode.ofSuccess();}
        public CompletableResultCode flush(){return CompletableResultCode.ofSuccess();}
        public CompletableResultCode shutdown(){return CompletableResultCode.ofSuccess();}
    }
    @Test void sameSdkExportsAndPersistsNativeTreeDespiteUnsampledHttpParent() throws Exception {
        var config=new AssessmentProperties();config.setEnabled(true);
        var jdbc=mock(JdbcTemplate.class);var processor=new AssessmentTraceProcessor(jdbc,config);
        var exporter=new CapturingExporter();
        var provider=SdkTracerProvider.builder().setSampler(AssessmentTraceConfiguration.businessSampler(Sampler.alwaysOff()))
            .addSpanProcessor(processor).addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        var telemetry=OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        var store=new ObservationStore(jdbc,config,new PayloadRedactor(new ObjectMapper()),telemetry,processor);
        try {
            String httpTrace="1234567890abcdef1234567890abcdef",httpSpan="1234567890abcdef";
            var ctx=store.begin("native-test",null,httpTrace,UUID.randomUUID(),httpSpan);
            var agent=store.span(ctx,"AGENT","planning-agent",null,"secret-prompt");
            var logical=store.span(agent.child(),"MODEL_CALL","planning-agent",null,null);
            var actual=store.span(logical.child(),"MODEL","stub",null,null);
            UUID group=UUID.randomUUID();actual.attempt(group,1);actual.ttft(5);
            actual.usage("stub",new ChatUsage(10,3,0d),"provider-id");
            actual.end("SUCCEEDED","secret-output",null);actual.end("FAILED",null,new RuntimeException("private-error"));
            logical.end("SUCCEEDED",null,null);agent.end("SUCCEEDED",null,null);
            store.finish(ctx,"REPLIED",999999,false);store.finish(ctx,"FAILED",0,false);store.flush();
            assertEquals(4,exporter.spans.size());
            assertTrue(exporter.spans.stream().allMatch(s->s.getTraceId().equals(httpTrace)&&s.getSpanContext().isSampled()));
            var run=exporter.spans.stream().filter(s->"RUN".equals(AssessmentTraceProcessor.value(s.getAttributes(),"kind"))).findFirst().orElseThrow();
            var model=exporter.spans.stream().filter(s->"MODEL".equals(AssessmentTraceProcessor.value(s.getAttributes(),"kind"))).findFirst().orElseThrow();
            var request=exporter.spans.stream().filter(s->"MODEL_CALL".equals(AssessmentTraceProcessor.value(s.getAttributes(),"kind"))).findFirst().orElseThrow();
            assertEquals(httpSpan,run.getParentSpanId());assertEquals(request.getSpanId(),model.getParentSpanId());
            assertEquals("10",AssessmentTraceProcessor.value(model.getAttributes(),"input_tokens"));
            assertEquals(group.toString(),AssessmentTraceProcessor.value(model.getAttributes(),"attempt_group"));
            assertFalse(exporter.spans.toString().contains("secret-prompt"));
            assertFalse(exporter.spans.toString().contains("secret-output"));
            assertEquals(1,mockingDetails(jdbc).getInvocations().stream().filter(i->i.getArgument(0).toString().startsWith("UPDATE assessment_runs SET status=CASE")).count());
            assertEquals("OTEL_V1",AssessmentTraceProcessor.VERSION);
            var remote=SpanContext.createFromRemoteParent(httpTrace,httpSpan,TraceFlags.getDefault(),TraceState.getDefault());
            var legacy=new ObservationContext(ctx.runId(),ctx.turnId(),"legacy-trace",null,null);
            assertEquals(httpTrace,legacy.child(UUID.randomUUID(),remote).traceId());
            var forced=provider.get("remote-parent-test").spanBuilder("business-under-unsampled-parent")
                .setParent(Context.root().with(Span.wrap(remote)))
                .setAttribute(AssessmentTraceProcessor.key("run_id"),ctx.runId().toString())
                .setAttribute(AssessmentTraceProcessor.key("kind"),"DIAGNOSTIC_TEST").startSpan();
            assertTrue(forced.isRecording());assertTrue(forced.getSpanContext().isSampled());forced.end();
        } finally {store.close();telemetry.close();}
    }

    @Test void diagnosticFailuresDoNotAffectDatabaseOrBusiness() throws Exception {
        var config=new AssessmentProperties();config.setEnabled(true);var jdbc=mock(JdbcTemplate.class);
        var processor=new AssessmentTraceProcessor(jdbc,config);
        SpanExporter failed=new CapturingExporter(){public CompletableResultCode export(Collection<SpanData> batch){return CompletableResultCode.ofFailure();}};
        try(var provider=SdkTracerProvider.builder().setSampler(Sampler.alwaysOn()).addSpanProcessor(processor).addSpanProcessor(SimpleSpanProcessor.create(failed)).build()){
            OpenTelemetry sdk=OpenTelemetrySdk.builder().setTracerProvider(provider).build();
            var store=new ObservationStore(jdbc,config,new PayloadRedactor(new ObjectMapper()),sdk,processor);
            var run=store.begin("jaeger-offline",null);var node=store.span(run,"MODEL","stub",null,null);
            node.end("SUCCEEDED",null,null);store.finish(run,"REPLIED",0,false);store.flush();
            assertEquals(false,processor.health().get("degraded"));
        }
    }

    @Test void databaseFailureMarksIngestionAndFlushRefusesPartialScoring() throws Exception {
        var config=new AssessmentProperties();config.setEnabled(true);var jdbc=mock(JdbcTemplate.class);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic offline"))
            .when(jdbc).update(anyString(),any(Object[].class));
        var store=new ObservationStore(jdbc,config,new PayloadRedactor(new ObjectMapper()));
        try {
            var run=assertDoesNotThrow(()->store.begin("db-offline",null));
            var node=assertDoesNotThrow(()->store.span(run,"MODEL","stub",null,null));
            assertDoesNotThrow(()->node.end("SUCCEEDED",null,null));
            assertDoesNotThrow(()->store.finish(run,"REPLIED",0,false));
            assertThrows(IllegalStateException.class,store::flush);
            assertEquals(true,store.health().get("degraded"));
            assertTrue(((Number)store.health().get("persistenceFailures")).longValue()>0);
        }finally{store.close();}
    }

    @Test void boundedQueueOverflowDoesNotBlockTheExecutingThread() throws Exception {
        var config=new AssessmentProperties();config.setEnabled(true);config.setQueueCapacity(1);
        var jdbc=mock(JdbcTemplate.class);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var first=new AtomicBoolean(true);
        doAnswer(i->{if(first.compareAndSet(true,false)){entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));}return 1;})
            .when(jdbc).update(anyString(),any(Object[].class));
        var store=new ObservationStore(jdbc,config,new PayloadRedactor(new ObjectMapper()));
        try {
            var run=store.begin("queue-full",null);assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertTimeout(java.time.Duration.ofSeconds(1),()->{
                var node=store.span(run,"MODEL","stub",null,"request");node.end("SUCCEEDED",null,null);store.finish(run,"REPLIED",0,false);
            });
            assertTrue(((Number)store.health().get("droppedWrites")).longValue()>0);
            release.countDown();
            // Once the blocked write completes there is room for the barrier.
            for(int n=0;n<100 && ((Number)store.health().get("queueDepth")).intValue()>0;n++)Thread.sleep(5);
            assertThrows(IllegalStateException.class,store::flush);
        }finally{release.countDown();store.close();}
    }

    @Test void incompleteScoreTraceNeverTriggersAnotherModelAttemptOrAPassingScore() throws Exception {
        var config=new AssessmentProperties();config.setEnabled(true);var jdbc=mock(JdbcTemplate.class);
        var mapper=new ObjectMapper();var store=spy(new ObservationStore(jdbc,config,new PayloadRedactor(mapper)));
        var count=new java.util.concurrent.atomic.AtomicInteger();
        io.agentscope.core.model.Model model=new io.agentscope.core.model.Model(){
            public String getModelName(){return "stub";}
            public reactor.core.publisher.Flux<io.agentscope.core.model.ChatResponse> stream(
                    List<io.agentscope.core.message.Msg> m,List<io.agentscope.core.model.ToolSchema> t,io.agentscope.core.model.GenerateOptions o){
                count.incrementAndGet();
                return reactor.core.publisher.Flux.just(io.agentscope.core.model.ChatResponse.builder().content(List.of(
                    io.agentscope.core.message.TextBlock.builder().text("{\"routeCoherence\":8,\"timeDensity\":8,\"preferenceMatch\":8,\"reason\":\"路线连贯\",\"evidence\":[\"西湖步行\"]}").build())).build());
            }
        };
        try {
            var run=store.begin("score-gap",null);
            doThrow(new IllegalStateException("synthetic missing trace")).when(store).flush(any(ObservationContext.class));
            var scorer=new PosthocScorer(new ObservationMiddleware(store),jdbc,mapper,new PayloadRedactor(mapper),new ObservedModel(model,store));
            scorer.score(run,"半日游","西湖步行");store.flush();
            assertEquals(1,count.get());
            var result=mockingDetails(jdbc).getInvocations().stream()
                .filter(i->i.getArgument(0).toString().startsWith("UPDATE assessment_evaluations SET status='ERROR'")).findFirst().orElseThrow();
            assertTrue(result.getArgument(1).toString().contains("TRACE_INCOMPLETE"));
            assertFalse(mockingDetails(jdbc).getInvocations().stream().anyMatch(i->i.getArgument(0).toString().startsWith("UPDATE assessment_evaluations SET status='SUCCEEDED'")));
        }finally{store.close();}
    }
}

