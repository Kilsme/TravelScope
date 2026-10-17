package com.travelscope.assessment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelscope.config.AppProperties;
import com.travelscope.config.AgentConfig.TaskWorkspaceService;
import com.travelscope.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class AssessmentLifecycleTest {
    @Test void allAdministratorRoutesDenyOrdinaryUsers() throws Exception {
        var controller=new AssessmentController(mock(AssessmentQueries.class),mock(AssessmentBatches.class),mock(AssessmentActions.class));
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();String id=UUID.randomUUID().toString();
        for(String path:List.of("/overview","/runs","/runs/"+id,"/runs/"+id+"/spans","/runs/"+id+"/artifacts","/runs/"+id+"/evaluations","/spans/"+id+"/payload","/cases","/batches","/batches/"+id,"/samples/"+id+"/runs","/comparisons?baselineId="+id+"&candidateId="+id))
            mvc.perform(get("/api/admin/assessment"+path).contextPath("/api").requestAttr(com.travelscope.security.AuthInterceptor.ATTR_USER_ROLE,"user")).andExpect(status().isForbidden());
        for(String path:List.of("/batches/"+id+"/cancel","/batches/"+id+"/retry-incomplete","/runs/"+id+"/evaluations"))
            mvc.perform(post("/api/admin/assessment"+path).contextPath("/api").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/assessment/batches").contextPath("/api").contentType("application/json").content("{\"requestKey\":\"test-key\",\"toolMode\":\"FIXTURE\",\"caseIds\":[\"EC-001\"]}")).andExpect(status().isForbidden());
    }
    @Test void duplicateKeyReturnsExistingBatchWithoutDispatchingOrCharging(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);var properties=new AssessmentProperties();properties.setEnabled(true);properties.setBatchesEnabled(true);
        UUID existing=UUID.randomUUID();when(jdbc.queryForList("SELECT id FROM assessment_batches WHERE request_key=?","same-request")).thenReturn(List.of(Map.of("id",existing)));
        var chat=mock(ChatService.class);
        var batches=new AssessmentBatches(jdbc,new ObjectMapper(),properties,chat,mock(ObservationStore.class),mock(TaskWorkspaceService.class),mock(PosthocScorer.class),mock(AssessmentQueries.class),new AppProperties());
        assertEquals(existing,batches.create(new AssessmentBatches.Request(List.of("EC-001"),"FIXTURE",3,"same-request")));
        verifyNoInteractions(chat);assertEquals(2,mockingDetails(jdbc).getInvocations().size());batches.close();
    }
    @Test void recoveryNeverResubmitsPaidCalls(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);var properties=new AssessmentProperties();properties.setEnabled(true);var chat=mock(ChatService.class);
        var batches=new AssessmentBatches(jdbc,new ObjectMapper(),properties,chat,mock(ObservationStore.class),mock(TaskWorkspaceService.class),mock(PosthocScorer.class),mock(AssessmentQueries.class),new AppProperties());
        batches.recover();verifyNoInteractions(chat);
        assertTrue(mockingDetails(jdbc).getInvocations().stream().anyMatch(i->i.getArgument(0).toString().contains("status='INTERRUPTED'")));
        batches.close();
    }
    @Test void retentionSeparatesBodiesFromMetrics() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);var properties=new AssessmentProperties();properties.setEnabled(true);
        var store=new ObservationStore(jdbc,properties,new PayloadRedactor(new ObjectMapper()));store.retain();
        verify(jdbc).update("DELETE FROM assessment_payloads WHERE created_at < now()-interval '30 days'");
        verify(jdbc).update("DELETE FROM assessment_runs WHERE started_at < now()-interval '180 days'");store.close();
    }
    @Test void onlyDeclaredDependenciesRestrictParallelCalls(){
        var spans=List.<Map<String,Object>>of(Map.of("name","poi","status","SUCCEEDED","ended_at","2026-10-17T00:00:01Z"),Map.of("name","route","started_at","2026-10-17T00:00:02Z"),Map.of("name","weather","started_at","2026-10-17T00:00:00Z"));
        assertEquals("PASS",AssessmentRules.dependency("poi","route",spans).status());
        assertEquals("FAIL",AssessmentRules.dependency("poi","weather",spans).status());
        assertEquals("UNKNOWN",AssessmentRules.dependency("missing","route",spans).status());
    }
    @Test void fixtureBlocksEveryExternalToolButLiveStillDelegates(){
        var toolkit=new io.agentscope.core.tool.Toolkit();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        for(String name:List.of("getWeather","web_search","run_shell"))toolkit.registerAgentTool(new io.agentscope.core.tool.AgentTool(){
            public String getName(){return name;}public String getDescription(){return "test";}public Map<String,Object> getParameters(){return Map.of("type","object");}
            public reactor.core.publisher.Mono<io.agentscope.core.message.ToolResultBlock> callAsync(io.agentscope.core.tool.ToolCallParam param){calls.incrementAndGet();return reactor.core.publisher.Mono.just(io.agentscope.core.message.ToolResultBlock.text("LIVE synthetic delegate"));}
        });
        FixtureTools.install(toolkit);FixtureTools.install(toolkit);
        var ctx=io.agentscope.core.agent.RuntimeContext.builder().userId("test").sessionId("fixture").put("assessment.toolMode","FIXTURE").build();
        for(String name:toolkit.getToolNames())toolkit.getTool(name).callAsync(io.agentscope.core.tool.ToolCallParam.builder().runtimeContext(ctx).build()).block();
        assertEquals(0,calls.get());
        ctx.put("assessment.toolMode","LIVE");toolkit.getTool("getWeather").callAsync(io.agentscope.core.tool.ToolCallParam.builder().runtimeContext(ctx).build()).block();assertEquals(1,calls.get());
    }

    @Test void frozenDatesAreDeterministicAndMissingInputStaysMissing(){
        var base=java.time.LocalDate.of(2026,10,17);
        for(var c:com.travelscope.eval.EvalCaseLoader.loadAll()){
            var frozen=CaseFreezer.freeze(c,base);assertEquals(frozen,CaseFreezer.freeze(c,base));
            if(c.category()==com.travelscope.eval.EvalCategory.MISSING_INFORMATION)assertEquals(c.userMessages(),frozen.userMessages());
            else assertTrue(java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(frozen.userMessages().getFirst()).find());
        }
    }

}
