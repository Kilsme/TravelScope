package com.travelscope.assessment;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.*;
import io.agentscope.core.message.*;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.ReActAgent;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AssessmentFrameworkTest {
    private Model raw(String name,boolean fail){return new Model(){
        public String getModelName(){return name;}
        public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options){
            return fail?Flux.error(new IllegalStateException("synthetic provider failure")):Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("stub result").build())).usage(new ChatUsage(7,3,0d)).build());
        }
    };}
    @Test void primaryAndFallbackAttemptsShareGroupButHaveSeparateUsage() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var properties=new AssessmentProperties();properties.setEnabled(true);
        var store=new ObservationStore(jdbc,properties,new PayloadRedactor(new ObjectMapper()));
        var gateway=new com.travelscope.agent.LlmGatewayModel(new ObservedModel(raw("primary",true),store),new ObservedModel(raw("fallback",false),store),io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("assessment-test"),Duration.ofSeconds(2),true);
        var root=store.begin("fallback",null);var runtime=RuntimeContext.builder().userId("test").sessionId("fallback").put(ObservationContext.KEY,root).build();
        ReActAgent.builder().name("test-agent").model(gateway).middlewares(List.of(new ObservationMiddleware(store))).maxIters(1).build().call("input",runtime).block(Duration.ofSeconds(5));store.flush();
        var updates=mockingDetails(jdbc).getInvocations().stream().filter(i->i.getArgument(0).toString().contains("SET attempt_group=")).toList();
        assertEquals(2,updates.size());assertEquals((UUID)updates.get(0).getArgument(1),(UUID)updates.get(1).getArgument(1));assertEquals(1,(Integer)updates.get(0).getArgument(2));assertEquals(2,(Integer)updates.get(1).getArgument(2));
        store.close();
    }
    @Test void declaredChildActuallyInheritsMiddleware(@TempDir Path workspace) throws Exception {
        var jdbc=mock(JdbcTemplate.class);var properties=new AssessmentProperties();properties.setEnabled(true);
        var store=new ObservationStore(jdbc,properties,new PayloadRedactor(new ObjectMapper()));
        var declaration=SubagentDeclaration.builder().name("declared-child").description("test child").inlineAgentsBody("stub").maxIters(1).build();
        var entries=HarnessAgent.builder().name("parent").model(new ObservedModel(raw("stub",false),store))
            .workspace(workspace.toString()).middlewares(List.of(new ObservationMiddleware(store)))
            .disableDefaultWorkspaceSkills().disableShellTool().subagent(declaration).buildSubagentEntries(workspace);
        var root=store.begin("child",null);var runtime=RuntimeContext.builder().userId("test").sessionId("child").put(ObservationContext.KEY,root).build();
        var entry=entries.stream().filter(e->e.name().equals("declared-child")).findFirst().orElseThrow();
        try(var child=(HarnessAgent)entry.factory().create(runtime)){child.call("hello",runtime).block(Duration.ofSeconds(5));store.flush();
        assertTrue(mockingDetails(jdbc).getInvocations().stream().anyMatch(i->i.getArgument(0).toString().contains("INSERT INTO assessment_spans")&&Arrays.asList(i.getArguments()).contains("declared-child")));
        }
        store.close();
    }
    @Test void fixedStubOverheadKeepsBusinessOutputIdentical() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var properties=new AssessmentProperties();properties.setEnabled(true);properties.setQueueCapacity(4096);
        var store=new ObservationStore(jdbc,properties,new PayloadRedactor(new ObjectMapper()));var root=store.begin("overhead",null);
        Model plain=raw("stub",false),observed=new ObservedModel(plain,store);
        for(int i=0;i<50;i++)observed.stream(List.of(),List.of(),null).contextWrite(c->c.put(ObservationContext.KEY,root)).blockLast();
        double[] baseline=new double[200],enabled=new double[200];
        for(int i=0;i<200;i++){
            long start=System.nanoTime();var a=plain.stream(List.of(),List.of(),null).blockLast();baseline[i]=(System.nanoTime()-start)/1e6;
            start=System.nanoTime();var b=observed.stream(List.of(),List.of(),null).contextWrite(c->c.put(ObservationContext.KEY,root)).blockLast();enabled[i]=(System.nanoTime()-start)/1e6;
            assertEquals(a.getContent().toString(),b.getContent().toString());
        }
        store.flush();Arrays.sort(baseline);Arrays.sort(enabled);
        Files.writeString(Path.of("target/assessment-overhead.json"),new ObjectMapper().writeValueAsString(Map.of("mode","fixed model stub + mocked JDBC; no network", "samples",200,"baseline_p50_ms",baseline[99],"baseline_p95_ms",baseline[189],"observed_p50_ms",enabled[99],"observed_p95_ms",enabled[189],"output_identical",true)));
        store.close();
    }
}
