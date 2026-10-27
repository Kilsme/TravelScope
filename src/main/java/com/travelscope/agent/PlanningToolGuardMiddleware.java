package com.travelscope.agent;

import com.travelscope.collab.TaskWorkspace;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 防止 planning-agent 对确定性落盘工具发生无界重试。 */
public class PlanningToolGuardMiddleware implements MiddlewareBase {

    static final String HINT_TOOL = "planning_guard_hint";
    private final TaskWorkspace taskWorkspace;
    private final Map<String, AtomicInteger> shortlistAttempts = new ConcurrentHashMap<>();

    public PlanningToolGuardMiddleware(TaskWorkspace taskWorkspace) {
        this.taskWorkspace = taskWorkspace;
    }

    @Override
    public int order() {
        return -750;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
            java.util.function.Function<ActingInput, Flux<AgentEvent>> next) {
        if (!ItineraryAgent.AGENT_NAME.equals(agent.getName())) {
            return next.apply(input);
        }
        String key = ctx.getUserId() + ":" + ctx.getSessionId();
        boolean shortlistExists = taskWorkspace.readPoiShortlist(ctx.getUserId(), ctx.getSessionId()).isPresent();
        List<ToolUseBlock> guarded = input.toolCalls().stream().map(call -> {
            if (!"select_poi_shortlist".equals(call.getName())) {
                return call;
            }
            int attempt = shortlistAttempts.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
            if (!shortlistExists && attempt == 1) {
                return call;
            }
            String reason = shortlistExists
                    ? "SHORTLIST_ALREADY_FINALIZED: poi_shortlist.json 已存在，禁止重复调用 select_poi_shortlist。"
                      + "立即进入 plan_route(sessionId)，不得再次检索或定案 POI。"
                    : "SHORTLIST_RETRY_LIMIT_REACHED: select_poi_shortlist 首次调用未落盘，后续调用已被拦截。"
                      + "不得再次调用 select_poi_shortlist；停止 POI 重试并结束 planning-agent；"
                      + "向 travel-master 明确报告草案未生成及首次业务失败原因。";
            return new ToolUseBlock(call.getId(), HINT_TOOL, Map.of("reason", reason));
        }).toList();
        return next.apply(new ActingInput(guarded));
    }
}
