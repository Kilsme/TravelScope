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
 * 非规划意图明确「禁止委派规划子 Agent」，规划意图明确「按流程委派」。
 * 与主 Agent 提示词中的委派规则形成双保险，确保闲聊和单点查询不会触发规划子 Agent。
 * </p>
 * <p>
 * 未写入意图属性（如分类失败回退）时不追加任何指令，主 Agent 按自身提示词自主路由。
 * </p>
 */
public class IntentRouterMiddleware implements MiddlewareBase {

    /** RuntimeContext 中意图类别的属性键（值为 IntentType.name()） */
    public static final String CTX_INTENT_KEY = "travelscope.intent";

    /**
     * RuntimeContext 中本轮会话协作目录的属性键（值为相对路径，如 "tasks/conv-13"）。
     * 由 ChatService 按会话写入，用于把用户+会话隔离的具体路径注入路由指令（需求 1）。
     */
    public static final String CTX_COLLAB_DIR_KEY = "travelscope.collab.dir";

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
            case "PLANNING" -> planningDirective(ctx);
            default -> "";
        };
    }

    /**
     * PLANNING 意图的路由指令：注入本会话隔离的协作路径与
     * 「intake 收口 → 登记 → 委派」的工具调用顺序
     * （委派门禁 PlanningGateMiddleware 会在代码层校验，未登记清单直接委派会被拦截；
     * intake-agent 的委派发生在登记之前，门禁已对其豁免）
     */
    private String planningDirective(RuntimeContext ctx) {
        String collabDir = ctx.get(CTX_COLLAB_DIR_KEY) != null
                ? String.valueOf(ctx.get(CTX_COLLAB_DIR_KEY)) : null;
        String sessionId = ctx.getSessionId();
        if (collabDir == null) {
            return """
                    【本轮路由指令】系统已判定本轮为完整行程规划意图：请按规划流程处理，\
                    先委派 intake-agent 收口需求（缺项反问，信息收齐前不要进入任务拆分），\
                    再用 create_task_backlog 工具把任务清单登记进任务容器，\
                    然后委派规划子 Agent，最后整合结果返回用户。""";
        }
        return """
                【本轮路由指令】系统已判定本轮为完整行程规划意图。本轮会话: %s，协作目录: %s（相对工作区根）。

                委派流程（系统在代码层强制校验，跳步会被拦截）：
                1. 委派 intake-agent 收口需求：任务说明中带上本轮用户消息与协作目录 %s。
                   - intake-agent 返回反问 → 原样转达给用户，本轮结束
                   - intake-agent 返回「信息已收齐」→ 读取 %s/intake_done.md，继续第 2 步
                2. 按需求拆分任务（每项含 taskId/描述/建议工具/优先级）
                3. 调用 create_task_backlog 工具登记清单：sessionId 填 "%s"，tasksJson 填任务 JSON 数组。
                   禁止用 write_file 代替本工具——容器以本工具为准
                4. 登记成功后调用 agent_spawn 委派 planning-agent，任务说明中必须写明：
                   「用 read_file 读取 %s/task_backlog.md 与 %s/intake_done.md 执行；
                   每完成一项任务调用 update_task_status 工具回报状态」
                5. 需要时调用 get_task_progress 查询未完成任务数；完成后读取 %s/ 下的 execution_result.md、\
                itinerary_draft.md 与 review_passed.md 整合输出
                """.formatted(sessionId, collabDir, collabDir, collabDir, sessionId,
                collabDir, collabDir, collabDir);
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
