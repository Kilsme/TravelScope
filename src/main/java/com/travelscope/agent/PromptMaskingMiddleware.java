package com.travelscope.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 提示词按需加载 Middleware（2026-10-11 P2-I，对照 ragent AgentSkillMaskingMiddleware
 * 的可见性过滤形态：未激活技能的说明不占系统提示词）
 * <p>
 * 问题：主 Agent 的 SYS_PROMPT（约 6k 字符）全量静态常驻——「三、行程规划流程 +
 * 四、共享任务区间 + 规划委派规则」约 2.8k 字符（~45%）只在 PLANNING 轮有意义，
 * CHAT/TOOL_CALL/RAG 轮（约 60% 流量）的每一次 ReAct 迭代都白付这笔 token。
 * </p>
 * <p>
 * 机制：与 {@link IntentRouterMiddleware} 同钩（onSystemPrompt，方向相反——它追加、
 * 本类裁剪底座）：按 RuntimeContext 中的本轮意图（ChatService 写入
 * {@link IntentRouterMiddleware#CTX_INTENT_KEY}）可见性过滤——
 * PLANNING → 全量提示词；未知意图（分类失败回退）→ 保守全量；
 * 其余（CHAT/TOOL_CALL/RAG）→ 只发常驻段 {@link TravelMasterAgent#SYS_PROMPT_CORE}。
 * </p>
 * <p>
 * 边界：子 Agent 提示词不做掩码（本身按任务域 scoped，只在 PLANNING 内运行）。
 * order=-10：先于 IntentRouterMiddleware(0)——先掩码底座，路由指令再追加尾部
 * （若顺序颠倒，掩码会把已追加的指令一并换掉）。
 * </p>
 */
public class PromptMaskingMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(PromptMaskingMiddleware.class);

    private final boolean enabled;

    public PromptMaskingMiddleware(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public int order() {
        return -10;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String sysPrompt) {
        if (!enabled) {
            return Mono.just(sysPrompt);
        }
        Object intent = ctx.get(IntentRouterMiddleware.CTX_INTENT_KEY);
        if (intent == null || IntentType.PLANNING.name().equals(intent)) {
            return Mono.just(sysPrompt);   // 规划轮 / 未知意图（保守）：全量
        }
        log.debug("提示词按需加载：意图={} → 掩掉规划按需段（仅发常驻段）", intent);
        return Mono.just(TravelMasterAgent.SYS_PROMPT_CORE);
    }
}
