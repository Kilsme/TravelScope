package com.travelscope.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ItineraryAgent 提示词形状测试（S2.6）：锚定新职责提示词的关键工具/契约锚点，
 * 并锁死 v3 旧词（子 Agent 名与 .md 文件名）不再出现。
 */
class ItineraryAgentPromptTest {

    // ---------- 用例 1：新锚点齐全（代码服务三件套 + JSON 契约文件名） ----------
    @Test
    void promptContainsNewToolAndContractAnchors() {
        assertThat(ItineraryAgent.SYS_PROMPT)
                .contains("retrieve_poi_candidates")
                .contains("plan_route")
                .contains("write_collab_file")
                .contains("review_passed.json")
                .contains("task_backlog.json")
                .contains("intake_result.json")
                .contains("poi_shortlist.json")
                .contains("itinerary_draft.json")
                .contains("reviewer-agent");
    }

    // ---------- 用例 2：v3 旧词清零（子 Agent 与 .md 产物名不再出现） ----------
    @Test
    void promptDropsLegacyWords() {
        assertThat(ItineraryAgent.SYS_PROMPT)
                .doesNotContain("poi-research")
                .doesNotContain("route-optimizer")
                .doesNotContain("task_backlog.md")
                .doesNotContain("intake_done.md")
                .doesNotContain("poi_shortlist.md")
                .doesNotContain("route_plan.md")
                .doesNotContain("itinerary_draft.md");
    }

    // ---------- 用例 3：轮数预算腰斩（两个循环器已代码化） ----------
    @Test
    void maxItersHalvedTo16() {
        assertThat(ItineraryAgent.MAX_ITERS).isEqualTo(16);
    }
}
