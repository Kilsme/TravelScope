package com.travelscope.assessment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelscope.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.mockito.Mockito.*;

/** Explicit opt-in: one short synthetic score against the configured provider, with no database writes. */
@EnabledIfEnvironmentVariable(named="ASSESSMENT_SCORE_LIVE_PROBE",matches="true")
class PosthocScorerLiveTest {
    @Test void configuredModelCanCompleteTheActualScoringPath() throws Exception {
        var config=new AppProperties();var gateway=config.getModelGateway();
        gateway.setBaseUrl(System.getenv("ASSESSMENT_SCORE_PROBE_BASE_URL"));gateway.setApiKey(System.getenv("ASSESSMENT_SCORE_PROBE_API_KEY"));gateway.setReviewerModel(System.getenv("ASSESSMENT_SCORE_PROBE_MODEL"));gateway.setThinkingEnabled(false);
        var mapper=new ObjectMapper();var jdbc=mock(JdbcTemplate.class);var store=new ObservationStore(jdbc,new AssessmentProperties(),new PayloadRedactor(mapper));
        long start=System.nanoTime();
        try {
            var scorer=new PosthocScorer(config,store,new ObservationMiddleware(store),jdbc,mapper,new PayloadRedactor(mapper),"");
            scorer.score(new ObservationContext(UUID.randomUUID(),UUID.randomUUID(),null,null,null),"杭州半日游，步行为主，预算100元。","09:00-10:00 在杭州西湖步行游览。10:00-10:30 乘公交返回酒店。预算100元：公交10元、午餐40元，余50元。");
            verify(jdbc).update(startsWith("UPDATE assessment_evaluations SET status='SUCCEEDED'"),anyString(),any(UUID.class));
            System.out.printf("LIVE_SCORE_PROBE model=%s status=SUCCEEDED duration_seconds=%.2f database_writes=0%n",gateway.getReviewerModel(),(System.nanoTime()-start)/1e9);
        }finally{store.close();}
    }
}
