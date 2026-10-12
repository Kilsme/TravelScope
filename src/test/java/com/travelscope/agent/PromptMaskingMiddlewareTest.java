package com.travelscope.agent;

import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PromptMaskingMiddleware 单测（P2-I 提示词按需加载，无环境门控）
 * <p>
 * 验证按意图的可见性过滤：PLANNING/未知意图全量、其余意图仅常驻段、
 * 开关关闭透传、分段拼接完整性（SYS_PROMPT = CORE + EXTENSION）。
 * </p>
 */
class PromptMaskingMiddlewareTest {

    private static RuntimeContext ctxWithIntent(String intent) {
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("conv-t").build();
        if (intent != null) {
            ctx.put(IntentRouterMiddleware.CTX_INTENT_KEY, intent);
        }
        return ctx;
    }

    private static String apply(PromptMaskingMiddleware m, RuntimeContext ctx) {
        return m.onSystemPrompt(null, ctx, TravelMasterAgent.SYS_PROMPT)
                .block(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("PLANNING 意图 → 全量提示词（含规划流程与共享任务区间）")
    void testPlanningGetsFullPrompt() {
        PromptMaskingMiddleware m = new PromptMaskingMiddleware(true);
        String out = apply(m, ctxWithIntent(IntentType.PLANNING.name()));

        assertEquals(TravelMasterAgent.SYS_PROMPT, out);
        assertTrue(out.contains("行程规划的处理流程"), "PLANNING 轮应含规划流程");
        assertTrue(out.contains("共享任务区间"), "PLANNING 轮应含共享区间契约");
    }

    @Test
    @DisplayName("CHAT/TOOL_CALL/RAG 意图 → 仅常驻段（掩掉规划按需段 ~45%）")
    void testNonPlanningIntentsMasked() {
        PromptMaskingMiddleware m = new PromptMaskingMiddleware(true);
        for (String intent : new String[]{IntentType.CHAT.name(), IntentType.TOOL_CALL.name(),
                IntentType.RAG.name()}) {
            String out = apply(m, ctxWithIntent(intent));

            assertEquals(TravelMasterAgent.SYS_PROMPT_CORE, out, intent + " 应只发常驻段");
            assertFalse(out.contains("行程规划的处理流程"), intent + " 不应含规划流程");
            assertFalse(out.contains("共享任务区间"), intent + " 不应含共享区间契约");
            assertTrue(out.contains("IMPORTANT"), intent + " 硬约束必须在");
            assertTrue(out.contains("输出风格"), intent + " 输出风格必须在");
            assertTrue(out.contains("不得"), intent + " 「不得委派」清单必须在");
        }
    }

    @Test
    @DisplayName("未知意图（未写入 ctx）→ 保守全量")
    void testUnknownIntentGetsFullPrompt() {
        PromptMaskingMiddleware m = new PromptMaskingMiddleware(true);
        String out = apply(m, ctxWithIntent(null));

        assertEquals(TravelMasterAgent.SYS_PROMPT, out, "意图缺失时应保守发全量");
    }

    @Test
    @DisplayName("开关关闭 → 原样透传（回退全量常驻）")
    void testDisabledPassthrough() {
        PromptMaskingMiddleware m = new PromptMaskingMiddleware(false);
        String out = apply(m, ctxWithIntent(IntentType.CHAT.name()));

        assertEquals(TravelMasterAgent.SYS_PROMPT, out);
    }

    @Test
    @DisplayName("分段拼接完整性：SYS_PROMPT = CORE + EXTENSION（防重构丢内容）")
    void testSegmentsComposeFullPrompt() {
        assertEquals(TravelMasterAgent.SYS_PROMPT_CORE + TravelMasterAgent.SYS_PROMPT_PLANNING_EXTENSION,
                TravelMasterAgent.SYS_PROMPT);
        assertTrue(TravelMasterAgent.SYS_PROMPT_CORE.length() > 2000, "常驻段应含主体内容");
        assertTrue(TravelMasterAgent.SYS_PROMPT_PLANNING_EXTENSION.length() > 1500,
                "按需段应含规划流程与共享区间");
    }
}
