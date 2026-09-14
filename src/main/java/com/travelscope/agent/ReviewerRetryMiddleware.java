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

/**
 * Reviewer 回炉 Middleware（v3 需求文档 FR-S08 / FR-U09：质检不通过自动回炉，≤2 次）
 * <p>
 * v3 骨架阶段为<b>空壳透传</b>：链路占位 + order 预留，拦截逻辑后续迭代实现。
 * </p>
 * <p>
 * TODO（回炉拦截逻辑，下一步迭代）：
 * <ol>
 *   <li>onActing 阶段监测 reviewer-agent 是否产出 review_report.md（不通过）</li>
 *   <li>不通过且回炉次数 &lt; 2 → 拦截 planning-agent 的「返回用户」回复，
 *       替换为「继续委派 Planner 按改进建议修订 itinerary_draft.md 后重新送审」</li>
 *   <li>回炉 ≥ 2 次 → 放行，master 整合时标注「当前最佳版本（已尽力）」</li>
 * </ol>
 * 预期挂载点：master 责任链（见 AgentConfig），order 取 -900，
 * 位于 PlanningGateMiddleware(-1000) 内层、SubagentsMiddleware(1) 外层。
 * </p>
 */
public class ReviewerRetryMiddleware implements MiddlewareBase {

    /** 回炉上限（FR-S08 / FR-U09） */
    public static final int MAX_REVIEW_RETRIES = 2;

    // TODO: 注入 TaskWorkspaceService / 回炉计数器（userId:sessionId 双键隔离），实现拦截逻辑

    @Override
    public int order() {
        // PlanningGateMiddleware(-1000) 内层；SubagentsMiddleware(默认 order=1) 外层
        return -900;
    }

    // ==================== 全阶段透传（骨架占位） ====================

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
        // TODO: 回炉拦截（见类注释）；骨架阶段直接放行
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        java.util.function.Function<ModelCallInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }
}
