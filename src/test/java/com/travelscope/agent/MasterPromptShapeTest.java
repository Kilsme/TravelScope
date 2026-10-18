package com.travelscope.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TravelMasterAgent 提示词形状测试（S2.8 master 瘦身）：锁死规划编排词汇清零、
 * 单点查询能力保留、MAX_ITERS 减半、ITINERARY_OUTPUT_FORMAT 常量已抽出。
 */
class MasterPromptShapeTest {

    // ---------- 用例 1：规划编排词汇清零（编排已收归 ChatService 代码） ----------
    @Test
    void promptDropsPlanningVocabulary() {
        assertThat(TravelMasterAgent.SYS_PROMPT)
                .doesNotContain("planning-agent")
                .doesNotContain("intake-agent")
                .doesNotContain("task_backlog")
                .doesNotContain("create_task_backlog")
                .doesNotContain("委派规划")
                .doesNotContain("PLANNING 意图");
    }

    // ---------- 用例 2：单点查询能力与硬约束保留 ----------
    @Test
    void promptKeepsSinglePointQueryCapabilities() {
        assertThat(TravelMasterAgent.SYS_PROMPT)
                .contains("天气技能")
                .contains("火车票技能")
                .contains("机票技能")
                .contains("IMPORTANT")
                .contains("输出风格")
                .contains("不得调用 agent_spawn");
    }

    // ---------- 用例 3：SYS_PROMPT = CORE；MAX_ITERS 减半；格式常量已抽出 ----------
    @Test
    void structureAndBudget() {
        assertThat(TravelMasterAgent.SYS_PROMPT).isEqualTo(TravelMasterAgent.SYS_PROMPT_CORE);
        assertThat(TravelMasterAgent.MAX_ITERS).isEqualTo(8);
        assertThat(TravelMasterAgent.ITINERARY_OUTPUT_FORMAT)
                .contains("🗺")
                .contains("✅ 已通过质量审阅")
                .contains("⚠️ 当前最佳版本（已尽力");
    }
}
