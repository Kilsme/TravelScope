package com.travelscope.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void planningMasterCannotCallBusinessToolsDirectly() {
        IntentRouterMiddleware middleware = new IntentRouterMiddleware();
        RuntimeContext ctx = RuntimeContext.builder().build();
        ctx.put(IntentRouterMiddleware.CTX_INTENT_KEY, "PLANNING");
        ctx.put(IntentRouterMiddleware.CTX_PLANNING_DELEGATION_KEY, Boolean.TRUE);
        Agent master = mock(Agent.class);
        when(master.getName()).thenReturn(TravelMasterAgent.AGENT_NAME);
        ActingInput input = new ActingInput(List.of(
                new ToolUseBlock("1", "select_poi_shortlist", Map.of()),
                new ToolUseBlock("2", "agent_spawn", Map.of())));
        AtomicReference<ActingInput> seen = new AtomicReference<>();

        middleware.onActing(master, ctx, input, in -> { seen.set(in); return Flux.empty(); }).blockLast();

        assertThat(seen.get().toolCalls()).extracting(ToolUseBlock::getName)
                .containsExactly("planning_guard_hint", "agent_spawn");
    }
}
