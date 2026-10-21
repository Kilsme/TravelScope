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
                .contains("select_poi_shortlist")
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

    // ---------- 用例 4：L1 轮次合并锚点（L1b 筛选前移 + L1c 终止指令衔接） ----------
    @Test
    void promptContainsRoundMergeAnchors() {
        assertThat(ItineraryAgent.SYS_PROMPT)
                // L1b：缓存命中候选池时 select 与其余数据工具同一首轮 response 并行
                .contains("【筛选前移】")
                .contains("一并并行发出")
                // L1b：前移后轮次防重复调用
                .contains("严禁重复调用")
                // L1c：plan_route 返回的终止指令直接原样收尾
                .contains("【系统终止指令】")
                .contains("原样回复收尾");
    }

    // ---------- 用例 5：L1d shortlist 4×days 硬截断锚点 ----------
    @Test
    void promptContainsShortlistCapAnchor() {
        assertThat(ItineraryAgent.SYS_PROMPT).contains("总数上限 4×days");
    }
}
