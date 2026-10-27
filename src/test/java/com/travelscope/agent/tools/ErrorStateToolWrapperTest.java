package com.travelscope.agent.tools;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ErrorStateToolWrapperTest {
    @Test
    void structuredDuplicateBusinessErrorBecomesRealErrorState() {
        AgentTool delegate = mock(AgentTool.class);
        when(delegate.getName()).thenReturn("select_poi_shortlist");
        ToolResultBlock success = ToolResultBlock.builder()
                .id("call-1").name("select_poi_shortlist")
                .output(List.of(TextBlock.builder().text("ERROR: {\"code\":\"DUPLICATE_POI\",\"retryable\":false}").build()))
                .state(ToolResultState.SUCCESS).build();
        when(delegate.callAsync(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.just(success));

        ToolResultBlock result = new ErrorStateToolWrapper(delegate)
                .callAsync(mock(ToolCallParam.class)).block();

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
    }
}
