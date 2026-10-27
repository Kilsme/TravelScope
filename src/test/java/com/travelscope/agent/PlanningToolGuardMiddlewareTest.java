package com.travelscope.agent;

import com.travelscope.collab.TaskWorkspace;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanningToolGuardMiddlewareTest {

    @Test
    void permitsFirstShortlistAttemptAndBlocksSecondWhenNoArtifact() {
        TaskWorkspace workspace = mock(TaskWorkspace.class);
        when(workspace.readPoiShortlist("u", "s")).thenReturn(java.util.Optional.empty());
        PlanningToolGuardMiddleware middleware = new PlanningToolGuardMiddleware(workspace);
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn(ItineraryAgent.AGENT_NAME);
        RuntimeContext ctx = RuntimeContext.builder().userId("u").sessionId("s").build();
        ActingInput call = new ActingInput(List.of(new ToolUseBlock("1", "select_poi_shortlist", Map.of())));
        AtomicReference<ActingInput> seen = new AtomicReference<>();

        middleware.onActing(agent, ctx, call, in -> { seen.set(in); return Flux.empty(); }).blockLast();
        assertThat(seen.get().toolCalls().getFirst().getName()).isEqualTo("select_poi_shortlist");

        middleware.onActing(agent, ctx, call, in -> { seen.set(in); return Flux.empty(); }).blockLast();
        assertThat(seen.get().toolCalls().getFirst().getName()).isEqualTo("planning_guard_hint");
        assertThat(seen.get().toolCalls().getFirst().getInput().get("reason").toString())
                .contains("SHORTLIST_RETRY_LIMIT_REACHED", "不得再次调用 select_poi_shortlist");

        middleware.onActing(agent, ctx, call, in -> { seen.set(in); return Flux.empty(); }).blockLast();
        assertThat(seen.get().toolCalls().getFirst().getName()).isEqualTo("planning_guard_hint");
    }
}
