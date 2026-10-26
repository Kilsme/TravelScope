package com.travelscope.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证主 Agent 同时保留直接查询能力和固定的两叶子协作协议。 */
class MasterPromptShapeTest {

    @Test
    void promptDefinesOrderedLeafDelegation() {
        assertThat(TravelMasterAgent.FULL_SYS_PROMPT)
                .contains("planning-agent")
                .contains("reviewer-agent")
                .contains("同步委派 planning-agent")
                .contains("两个子 Agent 都是叶子")
                .contains("每个子 Agent 本轮最多调用一次");
    }

    @Test
    void promptKeepsSinglePointQueryCapabilities() {
        assertThat(TravelMasterAgent.FULL_SYS_PROMPT)
                .contains("天气技能")
                .contains("火车票技能")
                .contains("机票技能")
                .contains("IMPORTANT")
                .contains("输出风格");
    }

    @Test
    void keepsBoundedIterationBudget() {
        assertThat(TravelMasterAgent.MAX_ITERS).isEqualTo(16);
    }
}
