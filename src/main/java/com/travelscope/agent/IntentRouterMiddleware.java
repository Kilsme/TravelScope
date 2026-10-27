package com.travelscope.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.message.ToolUseBlock;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** 规划委派标记；只有应用层完成需求采集与任务登记后才允许启动两个叶子 Agent。 */
    public static final String CTX_PLANNING_DELEGATION_KEY = "travelscope.planning-delegation";

    static final String PLANNING_GUARD_HINT = "planning_guard_hint";
    private static final Set<String> MASTER_PLANNING_ALLOWED_TOOLS = Set.of("agent_spawn", PLANNING_GUARD_HINT);

    private static boolean isGuardedPlanning(Agent agent, RuntimeContext ctx) {
        Object intent = ctx.get(CTX_INTENT_KEY);
        return TravelMasterAgent.AGENT_NAME.equals(agent.getName())
                && "PLANNING".equals(String.valueOf(intent))
                && Boolean.TRUE.equals(ctx.get(CTX_PLANNING_DELEGATION_KEY));
    }

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
                    【本轮路由指令】系统已判定本轮为知识型问题：使用消息中提供的知识库上下文回答；\
                    涉及实时信息时必须调用相应工具，禁止编造数据，禁止委派规划子 Agent。""";
            case "PLANNING" -> Boolean.TRUE.equals(ctx.get(CTX_PLANNING_DELEGATION_KEY)) ? """
                    【本轮路由指令】需求采集与任务登记已完成，开始执行完整行程规划。\
                    严格按照系统提示词的规划流程，依次同步委派 planning-agent 和 reviewer-agent；\
                     本轮主 Agent 只能调用 agent_spawn，禁止亲自调用 select_poi_shortlist、plan_route、\
                     assemble_itinerary 或任何其他业务工具；子 Agent 失败时也不得越权接管。\
                    禁止使用 general-purpose 或其他子 Agent，禁止跳过任一已登记阶段。""" : "";
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
        if (!isGuardedPlanning(agent, ctx) || input.toolCalls().stream()
                .allMatch(call -> MASTER_PLANNING_ALLOWED_TOOLS.contains(call.getName()))) {
            return next.apply(input);
        }
        List<ToolUseBlock> guarded = input.toolCalls().stream().map(call -> {
            if (MASTER_PLANNING_ALLOWED_TOOLS.contains(call.getName())) {
                return call;
            }
            String reason = "PLANNING_MASTER_TOOL_FORBIDDEN: travel-master 在完整规划中只能调用 agent_spawn；"
                    + "已拦截越权工具 " + call.getName() + "。若 planning-agent 失败，不得接管其业务工具，"
                    + "应继续同步委派 reviewer-agent 处理现有产物；无草案则如实结束。";
            return new ToolUseBlock(call.getId(), PLANNING_GUARD_HINT, Map.of("reason", reason));
        }).toList();
        return next.apply(new ActingInput(guarded));
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        java.util.function.Function<ModelCallInput, Flux<AgentEvent>> next) {
        if (!isGuardedPlanning(agent, ctx)) {
            return next.apply(input);
        }
        var visibleTools = input.tools().stream()
                .filter(tool -> "agent_spawn".equals(tool.getName()))
                .toList();
        return next.apply(new ModelCallInput(input.messages(), visibleTools, input.options(), input.model()));
    }
}
