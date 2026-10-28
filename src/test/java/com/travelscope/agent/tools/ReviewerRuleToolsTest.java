package com.travelscope.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.travelscope.collab.ReviewPassed;
import com.travelscope.service.RuleReviewService;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewerRuleToolsTest {

    @Test
    void delegatesRuleCheckAndReturnsStructuredVerdict() {
        RuleReviewService service = mock(RuleReviewService.class);
        when(service.check("u1", "conv-1")).thenReturn(new RuleReviewService.Outcome(
                RuleReviewService.Outcome.Verdict.FAIL,
                List.of("第二天时间冲突"), List.<ReviewPassed.ReviewWarning>of(), "修订路线",
                "TIMELINE_OVERLAP", true));
        ReviewerRuleTools tools = new ReviewerRuleTools(service);
        RuntimeContext context = RuntimeContext.builder().userId("u1").sessionId("conv-1").build();

        JSONObject result = JSON.parseObject(tools.check("conv-1", context));

        assertThat(result.getString("verdict")).isEqualTo("FAIL");
        assertThat(result.getBooleanValue("passed")).isFalse();
        assertThat(result.getString("reasonCode")).isEqualTo("TIMELINE_OVERLAP");
        assertThat(result.getBooleanValue("retryable")).isTrue();
        assertThat(result.getJSONArray("issues").getString(0)).contains("时间冲突");
        assertThat(result.getString("revisionTask")).isEqualTo("修订路线");
        assertThat(result.getJSONObject("actionRoute").getJSONArray("allowedActions"))
                .containsExactly("plan_route");
        assertThat(result.getJSONObject("actionRoute").getBooleanValue("mustRecheck")).isTrue();
        verify(service).check("u1", "conv-1");
    }

    @Test
    void rejectsCrossSessionCheck() {
        RuleReviewService service = mock(RuleReviewService.class);
        ReviewerRuleTools tools = new ReviewerRuleTools(service);
        RuntimeContext context = RuntimeContext.builder().userId("u1").sessionId("conv-1").build();

        assertThat(tools.check("conv-2", context)).startsWith("ERROR:");
    }
}
