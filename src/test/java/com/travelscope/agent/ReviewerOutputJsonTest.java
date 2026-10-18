package com.travelscope.agent;

import com.travelscope.agent.tools.CollabTools;
import com.travelscope.collab.CollabFileReader;
import com.travelscope.collab.CollabFileWriter;
import com.travelscope.collab.TaskWorkspace;
import com.travelscope.service.retrieval.PoiRetrievalService;
import com.travelscope.service.route.RoutePlannerServiceImpl;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * ReviewerAgent 输出 JSON 化（S2.7）测试：提示词锚点 + 通过/不通过两路径经
 * write_collab_file 落盘（落盘即过 CollabFileWriter 的 Schema 校验）+ 读回一致。
 */
class ReviewerOutputJsonTest {

    @TempDir
    Path tempDir;

    private TaskWorkspace workspace;
    private CollabTools tools;
    private RuntimeContext ctx;

    @BeforeEach
    void setUp() {
        workspace = new TaskWorkspace(new CollabFileWriter(tempDir.toString()), new CollabFileReader());
        tools = new CollabTools(mock(PoiRetrievalService.class), mock(RoutePlannerServiceImpl.class),
                workspace, new CollabFileWriter(tempDir.toString()));
        ctx = RuntimeContext.builder().userId("u1").sessionId("conv-r").build();
    }

    // ---------- 用例 1：提示词锚点——产物 JSON 化 + REVIEW_RESULT 裸标记保留 + 评分细则未动 ----------
    @Test
    void promptAnchorsJsonOutputs() {
        assertThat(ReviewerAgent.SYS_PROMPT)
                .contains("write_collab_file")
                .contains("review_passed.json")
                .contains("review_report.json")
                .contains("itinerary_draft.json")
                .contains("REVIEW_RESULT: PASS|FAIL 总分=xx 失败段=")
                .doesNotContain("review_passed.md")
                .doesNotContain("review_report.md")
                .doesNotContain("itinerary_draft.md")
                .doesNotContain("write_file");
        // 5 维评分细则、三段审核、通过标准一字未改（卡片点 3）
        assertThat(ReviewerAgent.SYS_PROMPT)
                .contains("完备性").contains("可行性").contains("时间冲突")
                .contains("费用预算").contains("POI 合理性")
                .contains("总分 ≥ 80").contains("无任一维度 < 12").contains("三段全部 pass");
    }

    // ---------- 用例 2：通过路径 → review_passed.json 落盘并读回一致 ----------
    @Test
    void passPathWritesReviewPassedJson() {
        String json = "{\"total\":91,\"minDimension\":15,"
                + "\"verified\":[\"第1天 西湖→灵隐寺 通勤核验一致\",\"天气核验一致\"],"
                + "\"passedAt\":\"2026-10-18 12:00:00\"}";

        String result = tools.write_collab_file("conv-r", "review_passed.json", json, ctx);

        assertThat(result).contains("已写入 tasks/conv-r/review_passed.json");
        var back = workspace.readReviewPassed("u1", "conv-r");
        assertThat(back).isPresent();
        assertThat(back.get().total()).isEqualTo(91);
        assertThat(back.get().minDimension()).isEqualTo(15);
        assertThat(back.get().verified()).containsExactly("第1天 西湖→灵隐寺 通勤核验一致", "天气核验一致");
    }

    // ---------- 用例 3：不通过路径 → review_report.json 落盘（五维和=total，过校验器 ±1 规则）并读回一致 ----------
    @Test
    void failPathWritesReviewReportJson() {
        String json = "{\"segments\":[{\"segment\":1,\"pass\":true,\"issues\":[]},"
                + "{\"segment\":2,\"pass\":false,\"issues\":[\"第1天 灵隐寺→西溪湿地 通勤 96 分钟，超 90 分钟上限\"]},"
                + "{\"segment\":3,\"pass\":true,\"issues\":[]}],"
                + "\"scores\":{\"completeness\":18,\"feasibility\":16,\"timeConflict\":12,"
                + "\"costBudget\":17,\"poiReasonableness\":15},\"total\":78,"
                + "\"suggestionsBySegment\":{\"2\":[\"第1天 灵隐寺→西溪湿地 拆入两天或改驾车\"]},"
                + "\"reviewedAt\":\"2026-10-18 12:00:00\"}";

        String result = tools.write_collab_file("conv-r", "review_report.json", json, ctx);

        assertThat(result).contains("已写入 tasks/conv-r/review_report.json");
        Optional<com.travelscope.collab.ReviewReport> back = workspace.readReviewReport("u1", "conv-r");
        assertThat(back).isPresent();
        assertThat(back.get().total()).isEqualTo(78);
        assertThat(back.get().segments().get(1).pass()).isFalse();
        assertThat(back.get().segments().get(1).issues())
                .containsExactly("第1天 灵隐寺→西溪湿地 通勤 96 分钟，超 90 分钟上限");
        assertThat(back.get().suggestionsBySegment().get("2"))
                .containsExactly("第1天 灵隐寺→西溪湿地 拆入两天或改驾车");
    }
}
