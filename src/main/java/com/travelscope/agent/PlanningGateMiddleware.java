package com.travelscope.agent;

import com.travelscope.service.TaskRegistry;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 规划委派门禁 Middleware（需求 2：代码层强制「先登记清单、再委派」）
 * <p>
 * 在 onActing 阶段拦截工具调用：当本轮为 PLANNING 意图且模型试图调用 {@code agent_spawn} 时，
 * 校验 {@link TaskRegistry} 中该用户会话是否已通过 {@code create_task_backlog} 登记任务清单：
 * <ul>
 *   <li>已登记 → 放行</li>
 *   <li>未登记 → <b>重写 ActingInput</b>：把 agent_spawn 调用替换为 {@code planning_gate_hint}
 *       （真实注册的工具，返回纠正指令），agent_spawn 根本不会执行；
 *       ReAct 循环读到纠正结果后自行补调 create_task_backlog 再重新委派</li>
 * </ul>
 * 这保证了「任务拆分必须写入容器」不依赖模型自觉——即使提示词被忽略，委派在代码层也无法绕过容器。
 * </p>
 * <p>
 * v3 豁免（需求文档 4.2 步骤 [5]/[6]）：需求收集发生在任务登记<b>之前</b>，
 * 因此对 {@code agent_id=intake-agent} 的 spawn 不做门禁校验，直接放行。
 * </p>
 */
public class PlanningGateMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(PlanningGateMiddleware.class);

    /** 被拦截时替换执行的工具名（必须在 Toolkit 中真实注册，见 TaskTools.planning_gate_hint） */
    public static final String GATE_HINT_TOOL = "planning_gate_hint";

    private final TaskRegistry taskRegistry;

    public PlanningGateMiddleware(TaskRegistry taskRegistry) {
        this.taskRegistry = taskRegistry;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     java.util.function.Function<ActingInput, Flux<AgentEvent>> next) {
        // 注意：RuntimeContext.get(String) 是 <T> T get(String)，泛型目标类型推断下
        // String.valueOf(ctx.get(...)) 会绑定到 String.valueOf(char[]) 重载（T 推断为 char[]），
        // 运行时 String→char[] 强转直接 ClassCastException——直接以 String 接收即可
        String intent = ctx.get(IntentRouterMiddleware.CTX_INTENT_KEY);
        List<ToolUseBlock> spawns = input.toolCalls().stream()
                .filter(t -> "agent_spawn".equals(t.getName()))
                .toList();
        if (spawns.isEmpty() || !"PLANNING".equals(intent)) {
            return next.apply(input);
        }
        // intake-agent 豁免：需求收集是任务登记的前置步骤，不受门禁约束
        boolean hasGatedSpawn = spawns.stream()
                .anyMatch(t -> !IntakeAgent.AGENT_NAME.equals(agentIdOf(t)));
        if (!hasGatedSpawn) {
            return next.apply(input);
        }

        String userId = ctx.getUserId();
        String sessionId = ctx.getSessionId();
        if (taskRegistry.hasBacklog(userId, sessionId)) {
            log.info("委派门禁放行: 用户={}, 会话={}, 任务清单已登记", userId, sessionId);
            return next.apply(input);
        }

        // 未登记清单 → 替换受门禁约束的 agent_spawn 为纠正提示工具，委派不会发生
        // （intake-agent 的 spawn 即使与受门禁的 spawn 同轮出现也原样保留）
        log.warn("委派门禁拦截: 用户={}, 会话={}, 任务清单未登记，agent_spawn 已被替换为 {}",
                userId, sessionId, GATE_HINT_TOOL);
        String hint = taskRegistry.gateHint(userId, sessionId);
        List<ToolUseBlock> rewritten = new ArrayList<>();
        for (ToolUseBlock call : input.toolCalls()) {
            if ("agent_spawn".equals(call.getName())
                    && !IntakeAgent.AGENT_NAME.equals(agentIdOf(call))) {
                rewritten.add(replaceWithHint(call, hint));
            } else {
                rewritten.add(call);
            }
        }
        return next.apply(new ActingInput(rewritten));
    }

    /**
     * 读取 agent_spawn 调用参数中的目标 agent_id（缺失视为未知，仍受门禁约束）
     */
    private String agentIdOf(ToolUseBlock call) {
        Object agentId = call.getInput() != null ? call.getInput().get("agent_id") : null;
        return agentId != null ? String.valueOf(agentId) : null;
    }

    /**
     * 构造同 ID 的纠正提示工具调用（保持 tool_use id 配对，模型侧能对应上结果）
     */
    private ToolUseBlock replaceWithHint(ToolUseBlock original, String hint) {
        return new ToolUseBlock(original.getId(), GATE_HINT_TOOL,
                java.util.Map.of("reason", hint));
    }

    // ==================== 其余阶段透传 ====================

    @Override
    public int order() {
        // 取最小值保证本 Middleware 处于责任链最外层：
        // MiddlewareChain 按列表顺序嵌套、第一个为最外层，SubagentsMiddleware（默认 order=1，
        // 直接处理 agent_spawn 不再下传）必须排在本门禁之后，拦截才生效
        return -1000;
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
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        java.util.function.Function<ModelCallInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }
}
