package com.travelscope.assessment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelscope.config.AgentConfig;
import com.travelscope.config.AppProperties;
import com.travelscope.common.TracingHelper;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssessmentReviewRegressionTest {
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void controllerUsesProjectContextPathExactlyOnce() throws Exception {
        var batches=mock(AssessmentBatches.class);when(batches.cases()).thenReturn(List.of());
        var mvc=MockMvcBuilders.standaloneSetup(new AssessmentController(mock(AssessmentQueries.class),batches,mock(AssessmentActions.class))).build();
        mvc.perform(get("/api/admin/assessment/cases").contextPath("/api").requestAttr("userRole","admin")).andExpect(status().isOk());
        mvc.perform(get("/api/api/admin/assessment/cases").contextPath("/api").requestAttr("userRole","admin")).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/assessment/cases").contextPath("/api").requestAttr("userRole","user")).andExpect(status().isForbidden());
    }

    @Test void stringBuilderJsonSecretsAreRedactedAndRemainReadable() throws Exception {
        String result=new PayloadRedactor(mapper).redact(new StringBuilder("{\"api_key\":\"synthetic-private-secret\",\"ok\":true}"));
        assertFalse(result.contains("synthetic-private-secret"));
        var parsed=mapper.readTree(result);assertEquals("[REDACTED]",parsed.get("api_key").asText());assertTrue(parsed.get("ok").asBoolean());
    }

    @Test void oversizeTextIsTruncatedNotPersistedVerbatim() {
        var redactor=new PayloadRedactor(mapper);
        String huge="A".repeat(150_000);
        // 顶层超长文本 → 占位符（图片 base64 随消息进观测 span 的防线）
        assertTrue(redactor.redact(huge).startsWith("[TRUNCATED: LONG_TEXT len=150000]"));
        // 嵌在 JSON 对象里的超长值同样截断（content_blocks/消息体场景）
        assertTrue(redactor.redact(java.util.Map.of("data",huge))
                .startsWith("{\"data\":\"[TRUNCATED: LONG_TEXT len=150000]\"}"));
        // 阈值内文本原样保留（脱敏规则不受影响）
        assertEquals("正常文本",redactor.redact("正常文本"));
    }

    @Test void embeddedJsonPreservesOuterAndInnerTypes() throws Exception {
        var payload=Map.of("text","{\"password\":\"synthetic-password\",\"count\":7}","nested",List.of(Map.of("authorization","synthetic-auth")));
        String result=new PayloadRedactor(mapper).redact(payload);assertFalse(result.contains("synthetic-password"));assertFalse(result.contains("synthetic-auth"));
        var outer=mapper.readTree(result);assertTrue(outer.get("text").isTextual());assertEquals(7,mapper.readTree(outer.get("text").asText()).get("count").asInt());
        assertEquals("[REDACTED]",outer.get("nested").get(0).get("authorization").asText());
    }

    @Test void actualModelOutputAndMultiplyEncodedJsonCannotLeak() throws Exception {
        String inner=mapper.writeValueAsString(Map.of("password","synthetic-model-password"));
        String encoded=mapper.writeValueAsString(inner);
        var response=ChatResponse.builder().content(List.of(TextBlock.builder().text(encoded).build())).build();
        String result=new PayloadRedactor(mapper).redact(List.of(response));
        assertFalse(result.contains("synthetic-model-password"));assertTrue(mapper.readTree(result).isArray());
        assertFalse(new PayloadRedactor(mapper).redact("noise \"password\":\"synthetic-malformed-secret\"").contains("synthetic-malformed-secret"));
    }

    @Test void realReviewerFactoryBuildsObservedScoringModelWithoutApiCalls() {
        var properties=new AppProperties();properties.getModelGateway().setBaseUrl("http://127.0.0.1:9/v1");properties.getModelGateway().setApiKey("SYNTHETIC-NOT-A-CREDENTIAL");
        var config=new AgentConfig(properties,null,null,null,null,null,null,TracingHelper.NOOP);
        ReflectionTestUtils.setField(config,"observationStore",mock(ObservationStore.class));
        try(var context=new AnnotationConfigApplicationContext()){
            // v5-F3：gatewayReviewerModel → reviewer 模型池（未配 model-pool 时回落单 provider）
            context.registerBean("gatewayReviewerModel",Model.class,()->config.gatewayReviewerModel(new com.travelscope.llm.ProviderProbeScheduler()));context.refresh();
            Model bean=context.getBean("gatewayReviewerModel",Model.class);assertInstanceOf(com.travelscope.llm.ModelProviderPool.class,bean);
            assertEquals("reviewer",((com.travelscope.llm.ModelProviderPool)bean).poolName());
        }
    }

    @Test void overviewRequiresExplicitBenchmarkModeAndLabelsOnlineDefault(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(),any(),any(),any())).thenReturn(Map.of("sample_count",1L));
        var queries=new AssessmentQueries(jdbc);
        assertEquals("ONLINE",queries.overview(null).get("source"));
        assertEquals("N/A",queries.overview(null).get("tool_mode"));
        assertEquals("LIVE",queries.overview("ASSESSMENT","LIVE").get("tool_mode"));
        assertEquals("FIXTURE",queries.overview("ASSESSMENT","FIXTURE").get("tool_mode"));
        verify(jdbc).queryForMap(contains("LEFT JOIN assessment_batches"),eq("ASSESSMENT"),eq("LIVE"),eq("LIVE"));
        verify(jdbc).queryForMap(contains("LEFT JOIN assessment_batches"),eq("ASSESSMENT"),eq("FIXTURE"),eq("FIXTURE"));
        int before=mockingDetails(jdbc).getInvocations().size();
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->queries.overview("ASSESSMENT"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->queries.overview("ONLINE","FIXTURE"));
        assertEquals(before,mockingDetails(jdbc).getInvocations().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"itinerary_draft.md", "itinerary_draft.json"})
    void manualScoreUsesFinalSnapshotInsteadOfEarlierDrafts(String name){
        UUID run=UUID.randomUUID();
        var queries=mock(AssessmentQueries.class);var scorer=mock(PosthocScorer.class);var jdbc=mock(JdbcTemplate.class);
        var properties=new AssessmentProperties();properties.setEnabled(true);
        when(queries.run(run)).thenReturn(Map.of("source","ONLINE","status","PLANNING_COMPLETED","turn_id",UUID.randomUUID()));
        when(queries.artifacts(run)).thenReturn(List.of(Map.of("name","itinerary_draft.md","body","rejected draft"),Map.of("name","itinerary_draft.md","body","final plan")));
        String body="itinerary_draft.json".equals(name)
                ? "{\"schemaVersion\":4,\"days\":[],\"markdownBody\":\"final plan\"}" : "final plan";
        when(queries.finalPlanningArtifact(run)).thenReturn(Optional.of(Map.of("name",name,"body",body)));
        when(jdbc.queryForList(startsWith("SELECT p.body"),eq(run))).thenReturn(List.of(Map.of("body","requirements")));
        var actions=new AssessmentActions(queries,scorer,jdbc,properties);
        try{
            actions.evaluate(run);
            verify(scorer,timeout(1000)).score(any(ObservationContext.class),eq("requirements"),eq("final plan"));
            verify(queries,never()).artifacts(run);
        }finally{actions.close();}
    }

    @Test void manualScoreCannotGuessLegacyFinalSnapshot(){
        UUID run=UUID.randomUUID();
        var queries=mock(AssessmentQueries.class);var scorer=mock(PosthocScorer.class);var jdbc=mock(JdbcTemplate.class);
        var properties=new AssessmentProperties();properties.setEnabled(true);
        when(queries.run(run)).thenReturn(Map.of("source","ONLINE","status","PLANNING_COMPLETED"));
        when(queries.finalPlanningArtifact(run)).thenReturn(Optional.empty());
        var actions=new AssessmentActions(queries,scorer,jdbc,properties);
        try{
            var error=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->actions.evaluate(run));
            assertEquals(org.springframework.http.HttpStatus.GONE,error.getStatusCode());
            verify(queries,never()).artifacts(run);
            verifyNoInteractions(scorer,jdbc);
        }finally{actions.close();}
    }
}
