package com.travelscope.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 意图路由 Middleware
 * <p>
 * 在 onSystemPrompt 阶段，根据 RuntimeContext 中的应用层意图分类结果
 * （ChatService 写入 {@link #CTX_INTENT_KEY}）向主 Agent 追加本轮路由指令：
 * 非规划意图（CHAT/TOOL_CALL/RAG）明确「禁止委派规划子 Agent」。
 * 与主 Agent 提示词中的委派规则形成双保险，确保闲聊和单点查询不会触发规划子 Agent。
 * </p>
 * <p>
 * S2.10：PLANNING 分支与协作目录键移除——PLANNING 已由 ChatService 代码编排
 * （S2.5），不再给 master 注规划指令。
 * </p>
 */
public class
IntentRouterMiddleware implements MiddlewareBase {

    /** RuntimeContext 中意图类别的属性键（值为 IntentType.name()） */
    public static final String CTX_INTENT_KEY = "travelscope.intent";

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String sysPrompt) {
        Object intent = ctx.get(CTX_INTENT_KEY);
        String directive = directiveFor(intent, ctx);
        return Mono.just(directive.isEmpty() ? sysPrompt : sysPrompt + "\n\n" + directive);
    }

    private String directiveFor(Object intent, RuntimeContext ctx) {
        if (intent == null) {
            return "";
        }
        return switch (intent.toString()) {
            case "CHAT" -> """
                    【本轮路由指令】系统已判定本轮为闲聊/能力咨询意图：直接以对话方式回答，\
                    不要调用任何工具，禁止委派规划子 Agent。""";
            case "TOOL_CALL" -> """
                    【本轮路由指令】系统已判定本轮为单点查询意图：直接调用对应工具获取实时数据后回答，\
                    禁止委派规划子 Agent，不要扩展为完整行程规划。""";
            case "RAG" -> """
                    【本轮路由指令】系统已判定本轮为知识型问题：知识库暂未接入，请基于你自己的知识直接回答；\
                    涉及实时信息（票价/余票/天气等）时说明你无法提供实时数据并建议查询渠道，\
                    禁止编造数据，禁止委派规划子 Agent。""";
            default -> "";
        };
    }

    // ==================== 其余阶段透传 ====================

    @Override
    public int order() {
        return 0;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    java.util.function.Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        java.util.function.Function<ReasoningInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     java.util.function.Function<ActingInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        java.util.function.Function<ModelCallInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }
}
