package com.travelscope.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

class NativeSubagentPromptTest {

    @Test
    void nativePromptDefinesExactlyTwoOrderedLeafRoles() {
        assertThat(TravelMasterAgent.FULL_SYS_PROMPT)
                .contains("planning-agent")
                .contains("reviewer-agent")
                .contains("两个子 Agent 都是叶子")
                .doesNotContain("你不得调用 agent_spawn 委派任何子 Agent");
    }

    @Test
    void planningDirectiveOnlyOpensDelegationWithNativeFlag() {
        IntentRouterMiddleware middleware = new IntentRouterMiddleware();
        RuntimeContext deterministic = RuntimeContext.builder().build();
        deterministic.put(IntentRouterMiddleware.CTX_INTENT_KEY, "PLANNING");
        RuntimeContext nativeContext = RuntimeContext.builder().build();
        nativeContext.put(IntentRouterMiddleware.CTX_INTENT_KEY, "PLANNING");
        nativeContext.put(IntentRouterMiddleware.CTX_PLANNING_DELEGATION_KEY, Boolean.TRUE);

        String base = "base";
        String unchanged = middleware.onSystemPrompt(null, deterministic, base).block();
        String extended = middleware.onSystemPrompt(null, nativeContext, base).block();

        assertThat(unchanged).isEqualTo(base);
        assertThat(extended)
                .contains("完整行程规划")
                .contains("planning-agent")
                .contains("reviewer-agent");
    }
}
