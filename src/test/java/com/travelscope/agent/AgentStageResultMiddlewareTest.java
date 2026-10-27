package com.travelscope.agent;

import com.travelscope.collab.AgentStageResult;
import com.travelscope.collab.CollabFileReader;
import com.travelscope.collab.CollabFileWriter;
import com.travelscope.collab.ItineraryDraft;
import com.travelscope.collab.ReviewPassed;
import com.travelscope.collab.TaskWorkspace;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentStageResultMiddlewareTest {

    @TempDir Path tempDir;
    private TaskWorkspace workspace;

    @BeforeEach
    void setUp() {
        workspace = new TaskWorkspace(new CollabFileWriter(tempDir.toString()), new CollabFileReader());
    }

    @Test
    void plannerCompletionWithDraftWritesDone() {
        workspace.writeItineraryDraft("u1", "conv-1",
                new ItineraryDraft(List.of(), null, List.of(), List.of(), "# draft"));
        AgentStageResultMiddleware middleware = new AgentStageResultMiddleware(workspace, AgentStageResult.Stage.PLANNER);
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn(ItineraryAgent.AGENT_NAME);
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("conv-1").build();

        middleware.onAgent(agent, ctx, mock(AgentInput.class), ignored -> Flux.empty()).blockLast();

        AgentStageResult result = workspace.readAgentStageResult(
                "u1", "conv-1", AgentStageResult.Stage.PLANNER).orElseThrow();
        assertThat(result.status()).isEqualTo(AgentStageResult.Status.DONE);
        assertThat(result.artifacts()).containsExactly(TaskWorkspace.FILE_ITINERARY_DRAFT);
    }

    @Test
    void plannerCompletionWithoutDraftWritesFailed() {
        AgentStageResultMiddleware middleware = new AgentStageResultMiddleware(workspace, AgentStageResult.Stage.PLANNER);
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn(ItineraryAgent.AGENT_NAME);
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("conv-1").build();

        middleware.onAgent(agent, ctx, mock(AgentInput.class), ignored -> Flux.empty()).blockLast();

        AgentStageResult result = workspace.readAgentStageResult(
                "u1", "conv-1", AgentStageResult.Stage.PLANNER).orElseThrow();
        assertThat(result.status()).isEqualTo(AgentStageResult.Status.FAILED);
        assertThat(result.reasonCode()).isEqualTo("DRAFT_MISSING");
    }

    @Test
    void reviewerRefusesToRunWhenPlannerIsNotDone() {
        workspace.writeAgentStageResult("u1", "conv-1", new AgentStageResult(
                AgentStageResult.Stage.PLANNER, AgentStageResult.Status.FAILED,
                "DRAFT_MISSING", false, List.of(), "2026-10-27 20:30:00"));
        AgentStageResultMiddleware middleware = new AgentStageResultMiddleware(workspace, AgentStageResult.Stage.REVIEWER);
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn(ReviewerAgent.AGENT_NAME);
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("conv-1").build();
        boolean[] invoked = {false};

        middleware.onAgent(agent, ctx, mock(AgentInput.class), ignored -> {
            invoked[0] = true;
            return Flux.empty();
        }).blockLast();

        assertThat(invoked[0]).isFalse();
        AgentStageResult result = workspace.readAgentStageResult(
                "u1", "conv-1", AgentStageResult.Stage.REVIEWER).orElseThrow();
        assertThat(result.status()).isEqualTo(AgentStageResult.Status.NOT_READY);
        assertThat(result.reasonCode()).isEqualTo("PLANNER_NOT_DONE");
    }

    @Test
    void reviewerCompletionWithReviewArtifactWritesDone() {
        workspace.writeAgentStageResult("u1", "conv-1", new AgentStageResult(
                AgentStageResult.Stage.PLANNER, AgentStageResult.Status.DONE,
                null, false, List.of(TaskWorkspace.FILE_ITINERARY_DRAFT), "2026-10-27 20:30:00"));
        workspace.writeItineraryDraft("u1", "conv-1",
                new ItineraryDraft(List.of(), null, List.of(), List.of(), "# draft"));
        workspace.writeReviewPassed("u1", "conv-1",
                new ReviewPassed(90, 15, List.of("ok"), "2026-10-27 20:31:00"));
        AgentStageResultMiddleware middleware = new AgentStageResultMiddleware(workspace, AgentStageResult.Stage.REVIEWER);
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn(ReviewerAgent.AGENT_NAME);
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("conv-1").build();

        middleware.onAgent(agent, ctx, mock(AgentInput.class), ignored -> Flux.<AgentEvent>empty()).blockLast();

        AgentStageResult result = workspace.readAgentStageResult(
                "u1", "conv-1", AgentStageResult.Stage.REVIEWER).orElseThrow();
        assertThat(result.status()).isEqualTo(AgentStageResult.Status.DONE);
        assertThat(result.artifacts()).containsExactly(TaskWorkspace.FILE_REVIEW_PASSED);
    }
}
