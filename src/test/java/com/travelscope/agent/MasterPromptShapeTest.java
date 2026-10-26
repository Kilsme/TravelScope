package com.travelscope.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证主 Agent 仅承担单点查询，不包含规划编排指令。 */
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

    @Test
    void keepsBoundedIterationBudget() {
        assertThat(TravelMasterAgent.MAX_ITERS).isEqualTo(8);
    }
}
