package com.travelscope.assessment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.travelscope.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PosthocScorerTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private static final String PLAN="09:00-10:00 游览西湖。10:00-10:30 乘公交返回酒店。";
    private static final String VALID="{\"routeCoherence\":8,\"timeDensity\":9,\"preferenceMatch\":8,\"reason\":\"路线连贯\",\"evidence\":[\"09:00-10:00 游览西湖\"]}";

    @Test void acceptsExplicitFlatSchemaAndWhitespaceAroundFence() throws Exception {
        var score=PosthocScorer.parseScore(mapper," \n```json\n"+VALID+"\n``` \n",PLAN);
        assertEquals(8,score.routeCoherence());assertTrue(score.valid(PLAN));
    }
    @Test void normalizesObservedNestedDimensionObjectsWithoutAnotherModelCall() throws Exception {
        var dimension=Map.of("score",8,"reason","沿途就近游览","evidence",List.of("09:00-10:00 游览西湖"));
        String json=mapper.writeValueAsString(Map.of("routeCoherence",dimension,"timeDensity",dimension,"preferenceMatch",dimension));
        var score=PosthocScorer.parseScore(mapper,json,PLAN);
        assertEquals(80,score.total());assertEquals(1,score.evidence().size());assertTrue(score.valid(PLAN));
    }
    @Test void missingFractionalOutOfRangeAndInventedEvidenceAreNeverZeroScores() {
        for(String invalid:List.of(VALID.replace("\"routeCoherence\":8,",""),VALID.replace("\"timeDensity\":9","\"timeDensity\":9.5"),VALID.replace("\"routeCoherence\":8","\"routeCoherence\":11"),VALID.replace("09:00-10:00 游览西湖","不存在的行程原文"),VALID+" trailing"))
            assertThrows(IllegalArgumentException.class,()->PosthocScorer.parseScore(mapper,invalid,PLAN));
    }
    @Test void requestHonorsThinkingSettingAndPersistsSuccessfulResult() throws Exception { exercise(false,false);exercise(true,false); }
    @Test void retryReceivesValidationFeedbackAndStillRequiresValidEvidence() throws Exception { exercise(false,true); }
    @Test void invalidEvidenceIsPersistedAsAnExplainedErrorWithoutATotalScore() throws Exception { exercise(false,true,true); }

    private void exercise(boolean thinking,boolean retry) throws Exception { exercise(thinking,retry,false); }
    private void exercise(boolean thinking,boolean retry,boolean alwaysInvalid) throws Exception {
        List<String> requests=new CopyOnWriteArrayList<>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            requests.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String text=alwaysInvalid||retry&&requests.size()==1?VALID.replace("09:00-10:00 游览西湖","编造的原文"):VALID;
            String chunk=mapper.writeValueAsString(Map.of("id","synthetic-score","object","chat.completion.chunk","model","synthetic-judge","choices",List.of(Map.of("index",0,"delta",Map.of("content",text),"finish_reason","stop")),"usage",Map.of("prompt_tokens",20,"completion_tokens",30,"total_tokens",50)));
            byte[] body=("data: "+chunk+"\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}
        });server.start();
        var jdbc=mock(JdbcTemplate.class);var config=new AppProperties();var gateway=config.getModelGateway();
        when(jdbc.queryForList(contains("SELECT r.data_complete"),any(Object[].class)))
            .thenReturn(List.of(Map.of("data_complete",true)));
        gateway.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1");gateway.setApiKey("synthetic-key");gateway.setReviewerModel("synthetic-judge");gateway.setThinkingEnabled(thinking);
        var properties=new AssessmentProperties();properties.setEnabled(true);
        var store=new ObservationStore(jdbc,properties,new PayloadRedactor(mapper));
        try {
            // v5-F3：PosthocScorer 改注入模型——测试自建与 reviewer 池 GLM provider 同构的
            // ObservedModel(GLMFormatter + thinking 透传)，断言口径不变（请求体 thinking 开关）
            var model=new ObservedModel(io.agentscope.extensions.model.openai.OpenAIChatModel.builder()
                .baseUrl(gateway.getBaseUrl()).apiKey(gateway.getApiKey()).modelName(gateway.getReviewerModel()).stream(true)
                .formatter(new io.agentscope.extensions.model.openai.compat.glm.GLMFormatter())
                .generateOptions(io.agentscope.core.model.GenerateOptions.builder()
                    .additionalBodyParam("thinking",Map.of("type",thinking?"enabled":"disabled")).build())
                .nativeStructuredOutput(gateway.isNativeStructuredOutput()).nativeStructuredOutputWithTools(gateway.isNativeStructuredOutputWithTools())
                .build(),store);
            var scorer=new PosthocScorer(new ObservationMiddleware(store),jdbc,mapper,new PayloadRedactor(mapper),model);
            scorer.score(new ObservationContext(UUID.randomUUID(),UUID.randomUUID(),null,null,null),"杭州半日步行游览",PLAN);store.flush();
            if(alwaysInvalid){
                var failure=mockingDetails(jdbc).getInvocations().stream().filter(i->i.getArgument(0).toString().startsWith("UPDATE assessment_evaluations SET status='ERROR'")).findFirst().orElseThrow();
                var error=mapper.readTree((String)failure.getArgument(1));assertEquals("INVALID_SCORE_EVIDENCE",error.path("errorCode").asText());assertEquals(2,error.path("attempts").asInt());assertFalse(error.has("total"));assertFalse(error.path("message").asText().isBlank());
            }else verify(jdbc).update(startsWith("UPDATE assessment_evaluations SET status='SUCCEEDED'"),anyString(),any(UUID.class));
            assertEquals(retry?2:1,requests.size());
            for(String request:requests){var body=mapper.readTree(request);assertEquals(thinking?"enabled":"disabled",body.path("thinking").path("type").asText());assertTrue(body.path("messages").toString().contains("值必须直接是整数"));}
            if(retry)assertTrue(requests.get(1).contains("上次输出未通过校验"));
        }finally{store.close();server.stop(0);}
    }
}
