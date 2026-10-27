package com.travelscope.agent.tools;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.Map;

/** 将遗留工具的 "ERROR:" 文本结果提升为 AgentScope 的 ERROR 状态。 */
public record ErrorStateToolWrapper(AgentTool delegate) implements AgentTool {
    @Override public String getName() { return delegate.getName(); }
    @Override public String getDescription() { return delegate.getDescription(); }
    @Override public Map<String, Object> getParameters() { return delegate.getParameters(); }
    @Override public Boolean getStrict() { return delegate.getStrict(); }
    @Override public Map<String, Object> getOutputSchema() { return delegate.getOutputSchema(); }
    @Override public boolean isReadOnly() { return delegate.isReadOnly(); }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return delegate.callAsync(param).map(result -> {
            if (result.getState() != ToolResultState.SUCCESS) {
                return result;
            }
            String text = result.getOutput().stream()
                    .filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast)
                    .map(TextBlock::getText)
                    .reduce("", String::concat)
                    .stripLeading();
            return text.startsWith("ERROR") ? result.withState(ToolResultState.ERROR) : result;
        });
    }
}
