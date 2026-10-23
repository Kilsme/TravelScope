package com.travelscope.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.travelscope.common.TurnCancellation;
import com.travelscope.config.AppProperties;
import com.travelscope.assessment.ObservationContext;
import com.travelscope.repository.ConversationRepository;
import io.agentscope.core.model.Model;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ChatCancellationTest {
    @Test void cancelledTurnFinalizationDoesNotScheduleSummary() {
        var service = mock(ChatService.class, CALLS_REAL_METHODS);
        var summaries = mock(ConversationSummaryService.class);
        ReflectionTestUtils.setField(service, "conversationSummaryService", summaries);
        var execution = new ChatService.TurnExecution();
        execution.cancel();
        service.executionFinished(execution, "CANCELLED");
        service.executionFinished(execution, "CANCELLED");
        verifyNoInteractions(summaries);
    }

    @Test void cancelledSummaryDoesNotQueryOrStartModel() {
        var conversations = mock(ConversationService.class);
        var repository = mock(ConversationRepository.class);
        var model = mock(Model.class);
        var service = new ConversationSummaryService(conversations, repository, model, new AppProperties());
        var token = new TurnCancellation();
        var observation = new ObservationContext(UUID.randomUUID(), UUID.randomUUID(), "test", null, null).withCancellation(token);
        token.cancel();
        service.maybeSummarizeAsync(1L, 1L, observation);
        verifyNoInteractions(conversations, repository, model);
    }
}
