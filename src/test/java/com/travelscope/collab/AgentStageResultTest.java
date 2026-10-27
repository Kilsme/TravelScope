package com.travelscope.collab;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentStageResultTest {

    @Test
    void contractCarriesStableStageStatusAndArtifacts() {
        AgentStageResult result = new AgentStageResult(
                AgentStageResult.Stage.PLANNER,
                AgentStageResult.Status.DONE,
                null, false, List.of(TaskWorkspace.FILE_ITINERARY_DRAFT),
                "2026-10-27 20:30:00");

        assertThat(result.schemaVersion()).isEqualTo(4);
        assertThat(result.stage()).isEqualTo(AgentStageResult.Stage.PLANNER);
        assertThat(result.status()).isEqualTo(AgentStageResult.Status.DONE);
        assertThat(result.artifacts()).containsExactly("itinerary_draft.json");
    }

    @Test
    void doneRequiresAtLeastOneArtifact() {
        assertThatThrownBy(() -> new AgentStageResult(
                AgentStageResult.Stage.REVIEWER,
                AgentStageResult.Status.DONE,
                null, false, List.of(), "2026-10-27 20:30:00"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
