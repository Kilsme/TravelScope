package com.travelscope.assessment;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.*;
import io.agentscope.core.message.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;
import java.util.*;
import java.math.BigDecimal;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AssessmentUnitTest {
    @Test void immutableAssociationDoesNotFollowLatestSession(){
        UUID run=UUID.randomUUID(),turn=UUID.randomUUID(),parent=UUID.randomUUID();
        var root=new ObservationContext(run,turn,"trace",null,null);
        assertNull(root.parentId());assertEquals(parent,root.child(parent).parentId());assertEquals(turn,root.child(parent).turnId());
        assertNotEquals(root.runId(),new ObservationContext(UUID.randomUUID(),UUID.randomUUID(),"other",null,null).runId());
    }
    @Test void redactNestedSecretsAndPersonalData(){
        var r=new PayloadRedactor(new ObjectMapper());
        String result=r.redact(Map.of("password","hunter2","nested",Map.of("api_key","private-secret"),"message","Bearer abc.def 13812345678 a@example.com 110101199001010011"));
        for(String secret:List.of("hunter2","private-secret","abc.def","13812345678","a@example.com","110101199001010011"))assertFalse(result.contains(secret),result);
    }
    @Test void streamUsageIsFinalSnapshotAndUnknownIsNull() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);AssessmentProperties properties=new AssessmentProperties();properties.setEnabled(true);
        var price=new AssessmentProperties.Price();price.setInputPerMillion(new BigDecimal("2"));price.setOutputPerMillion(new BigDecimal("4"));properties.getPrices().put("stub",price);
        try(var ignored=new AutoCloseable(){public void close(){}}){
            var store=new ObservationStore(jdbc,properties,new PayloadRedactor(new ObjectMapper()));
            Model raw=new Model(){public String getModelName(){return "stub";}public Flux<ChatResponse> stream(List<Msg> m,List<ToolSchema> t,GenerateOptions o){return Flux.just(
                ChatResponse.builder().usage(new ChatUsage(10,1,0d)).build(),ChatResponse.builder().usage(new ChatUsage(10,3,0d)).build());}};
            var ctx=store.begin("test",null);
            new ObservedModel(raw,store).stream(List.of(),List.of(),null).contextWrite(c->c.put(ObservationContext.KEY,ctx)).blockLast();store.flush();
            var calls=mockingDetails(jdbc).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")&&i.getArgument(0).toString().contains("SET model=")).toList();
            assertFalse(calls.isEmpty());
            Object[] args=java.util.Arrays.copyOfRange(calls.getFirst().getArguments(),1,calls.getFirst().getArguments().length);
            assertEquals(10L,args[1]);assertEquals(3L,args[2]);assertEquals("ACTUAL",args[4]);assertEquals(new BigDecimal("0.000032"),((BigDecimal)args[6]).stripTrailingZeros());
            store.close();
        }
    }
    @Test void hardRulesRejectOverlapAndBudgetMismatch(){
        String plan="第1天\n09:00-10:00 A\n09:30-11:00 B\n- 费用项:交通=50元\n- 费用项:门票=60元\n合计:100元";
        var checks=HardConstraintRules.check(plan,1,new BigDecimal("100"));
        assertEquals("PASS",checks.get(0).status());assertEquals("FAIL",checks.get(1).status());assertEquals("FAIL",checks.get(2).status());
    }
    @Test void unavailableEvidenceIsUnknown(){assertTrue(HardConstraintRules.check("一个不错的行程",null,null).stream().allMatch(r->r.status().equals("UNKNOWN")));}
    @Test void rubricRequiresExactArtifactEvidence(){
        assertFalse(new PosthocScorer.Score(10,10,10,"reason",List.of("invented")).valid("actual"));
        assertTrue(new PosthocScorer.Score(8,8,8,"reason",List.of("actual")).valid("actual"));
        assertEquals(80,new PosthocScorer.Score(8,8,8,"reason",List.of("actual")).total());
    }
    @Test void queryPaginationAndAdministratorEnforced() {
        var q=mock(AssessmentQueries.class);var controller=new AssessmentController(q,mock(AssessmentBatches.class),mock(AssessmentActions.class));
        var request=new org.springframework.mock.web.MockHttpServletRequest();
        var forbidden=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.runs(request,null,0,25));
        assertEquals(403,forbidden.getStatusCode().value());verifyNoInteractions(q);
        request.setAttribute(com.travelscope.security.AuthInterceptor.ATTR_USER_ROLE,"admin");controller.runs(request,null,0,25);verify(q).runs(null,0,25,null,null,null);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.runs(request,null,-1,25));
    }
    @Test void actualFrameworkModelCallCarriesExplicitAssociation() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);var properties=new AssessmentProperties();properties.setEnabled(true);
        var store=new ObservationStore(jdbc,properties,new PayloadRedactor(new ObjectMapper()));
        Model raw=new Model(){public String getModelName(){return "stub";}public Flux<ChatResponse> stream(List<Msg> m,List<ToolSchema> t,GenerateOptions o){return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("ok").build())).usage(new ChatUsage(3,2,0d)).build());}};
        var ctx=store.begin("framework",null);
        var runtime=io.agentscope.core.agent.RuntimeContext.builder().userId("test").sessionId("framework").put(ObservationContext.KEY,ctx).build();
        var agent=io.agentscope.core.ReActAgent.builder().name("stub-agent").model(new ObservedModel(raw,store)).middlewares(List.of(new ObservationMiddleware(store))).maxIters(1).build();
        agent.call("hello",runtime).block(java.time.Duration.ofSeconds(5));store.flush();
        var inserts=mockingDetails(jdbc).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")&&i.getArgument(0).toString().contains("INSERT INTO assessment_spans")).toList();
        assertTrue(inserts.stream().anyMatch(i->java.util.Arrays.asList(i.getArguments()).contains("MODEL")),"Raw model must inherit Reactor association through AgentScope");
        for(var insert:inserts)assertEquals(ctx.runId(),insert.getArgument(2));
        store.close();
    }
    @Test void cancellationSettlesUnknownUsageWithoutInventingZero() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);var properties=new AssessmentProperties();properties.setEnabled(true);
        var store=new ObservationStore(jdbc,properties,new PayloadRedactor(new ObjectMapper()));
        Model raw=new Model(){public String getModelName(){return "stub";}public Flux<ChatResponse> stream(List<Msg> m,List<ToolSchema> t,GenerateOptions o){return Flux.never();}};
        var ctx=store.begin("cancel",null);var subscription=new ObservedModel(raw,store).stream(List.of(),List.of(),null).contextWrite(c->c.put(ObservationContext.KEY,ctx)).subscribe();
        subscription.dispose();store.flush();
        var updates=mockingDetails(jdbc).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")&&i.getArgument(0).toString().contains("SET model=")).toList();
        assertEquals(1,updates.size());assertNull(updates.getFirst().getArgument(2));assertNull(updates.getFirst().getArgument(3));assertEquals("UNKNOWN",updates.getFirst().getArgument(5));
        store.close();
    }

    @Test void partialUsagePreservesKnownFieldsWithoutSummingSnapshots(){
        var usage=new UsageSnapshot();usage.accept(null);assertNull(usage.value());
        usage.accept(new ChatUsage(10,1,0d));usage.accept(new ChatUsage(0,3,0d));
        assertEquals(10,usage.value().getInputTokens());assertEquals(3,usage.value().getOutputTokens());
        var partial=new UsageSnapshot();partial.accept(new ChatUsage(4,0,0d));assertEquals(4,partial.value().getInputTokens());assertEquals(0,partial.value().getOutputTokens());
    }

    @Test void concurrentSharedWorkspaceCannotMasqueradeAsVerifiedArtifact() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);var properties=new AssessmentProperties();properties.setEnabled(true);
        var store=new ObservationStore(jdbc,properties,new PayloadRedactor(new ObjectMapper()));
        var first=store.begin("same-session",null);var second=store.begin("same-session",null);
        assertNotEquals(first.runId(),second.runId());
        store.finish(first,"PLANNING_COMPLETED",10,true);store.finish(second,"PLANNING_COMPLETED",12,true);
        assertThrows(IllegalStateException.class,store::flush);
        var terminal=mockingDetails(jdbc).getInvocations().stream().filter(i->i.getArgument(0).toString().startsWith("UPDATE assessment_runs SET status=CASE")).toList();
        assertEquals(2,terminal.size());for(var call:terminal){assertEquals("INCOMPLETE",call.getArgument(1));assertEquals(false,call.getArgument(8));assertEquals(false,call.getArgument(9));}
        store.close();
    }

    @Test void multipleEditsPinTheExactFinalPayloadEvenWhenContentIsIdentical() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var properties=new AssessmentProperties();properties.setEnabled(true);
        var store=new ObservationStore(jdbc,properties,new PayloadRedactor(new ObjectMapper()));
        try{
            var run=store.begin("final-artifact",null);
            String path="tasks/final-artifact/itinerary_draft.md";
            store.recordArtifactMutation(run,UUID.randomUUID(),path,"rejected draft");
            store.recordArtifactMutation(run,UUID.randomUUID(),path,"final plan");
            store.recordArtifactMutation(run,UUID.randomUUID(),path,"final plan");
            assertEquals("final plan",store.planningArtifact(run));
            store.finish(run,"PLANNING_COMPLETED",10,true);store.flush();
            var artifacts=mockingDetails(jdbc).getInvocations().stream()
                .filter(i->i.getArgument(0).toString().startsWith("INSERT INTO assessment_payloads")&&"ARTIFACT".equals(i.getArgument(4))).toList();
            var terminal=mockingDetails(jdbc).getInvocations().stream()
                .filter(i->i.getArgument(0).toString().startsWith("UPDATE assessment_runs SET status=CASE")).findFirst().orElseThrow();
            assertEquals(3,artifacts.size());
            assertNotEquals((UUID)artifacts.get(1).getArgument(1),(UUID)artifacts.getLast().getArgument(1));
            assertEquals((UUID)artifacts.getLast().getArgument(1),(UUID)terminal.getArgument(11));
        }finally{store.close();}
    }

}
