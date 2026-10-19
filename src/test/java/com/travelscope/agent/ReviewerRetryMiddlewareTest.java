package com.travelscope.agent;

import com.travelscope.collab.CollabFileReader;
import com.travelscope.collab.CollabFileWriter;
import com.travelscope.collab.TaskWorkspace;
import com.travelscope.common.TracingHelper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReviewerRetryMiddleware 保险丝单元测试（卡 B：拦 reviewer 修订轮次，上限 2 次）
 * <p>
 * 卡 B 起回炉闭环移入 reviewer 内部，保险丝从「拦 planner 第 3+ 次 spawn」改为
 * 「拦 reviewer 第 3+ 次写 itinerary_draft.json」。用 mock Agent 名 + write_collab_file
 * 调用模拟 reviewer 修订；Fake next 记录放行/拦截。
 * </p>
 */
class ReviewerRetryMiddlewareTest {

    @TempDir
    Path tempDir;

    private TaskWorkspace workspace;
    private ReviewerRetryMiddleware middleware;
    private Agent reviewerAgent;
    private Agent plannerAgent;

    @BeforeEach
    void setUp() {
        workspace = new TaskWorkspace(
                new CollabFileWriter(tempDir.toString()), new CollabFileReader());
        middleware = new ReviewerRetryMiddleware(workspace);
        reviewerAgent = mock(Agent.class);
        when(reviewerAgent.getName()).thenReturn(ReviewerAgent.AGENT_NAME);
        plannerAgent = mock(Agent.class);
        when(plannerAgent.getName()).thenReturn("planning-agent");
    }

    /** 记录 next 收到的输入（模拟中间件链放行） */
    private static final class Recorder {
        final AtomicReference<ActingInput> last = new AtomicReference<>();

        Flux<io.agentscope.core.event.AgentEvent> pass(ActingInput input) {
            last.set(input);
            return Flux.empty();
        }
    }

    private static RuntimeContext ctx() {
        return RuntimeContext.builder().userId("u1").sessionId("conv-t").build();
    }

    /** 构造 reviewer 写 itinerary_draft.json 的修订调用 */
    private static ActingInput draftWrite() {
        return new ActingInput(List.of(new ToolUseBlock("id-1", "write_collab_file",
                Map.of("file", "itinerary_draft.json", "contentJson", Map.of()))));
    }

    // ==================== 修订计次与拦截 ====================

    @Test
    @DisplayName("第 1/2 次修订放行（≤2 次上限内）")
    void testFirstTwoRevisionsPassThrough() {
        for (int i = 1; i <= 2; i++) {
            Recorder r = new Recorder();
            middleware.onActing(reviewerAgent, ctx(), draftWrite(), r::pass);
            assertEquals("write_collab_file", r.last.get().toolCalls().get(0).getName(),
                    "第 " + i + " 次修订应放行");
        }
    }

    @Test
    @DisplayName("第 3 次修订被保险丝拦截，改写为 review_retry_hint")
    void testThirdRevisionIntercepted() {
        for (int i = 0; i < 2; i++) {
            middleware.onActing(reviewerAgent, ctx(), draftWrite(), input -> Flux.empty());
        }
        Recorder r = new Recorder();
        middleware.onActing(reviewerAgent, ctx(), draftWrite(), r::pass);

        ToolUseBlock rewritten = r.last.get().toolCalls().get(0);
        assertEquals(ReviewerRetryMiddleware.RETRY_HINT_TOOL, rewritten.getName(),
                "第 3 次应被改写为 hint 工具");
        assertEquals("id-1", rewritten.getId(), "保持 tool_use id 配对");
        String reason = String.valueOf(rewritten.getInput().get("reason"));
        assertTrue(reason.contains("RETRY_LIMIT_REACHED") && reason.contains("已尽力"),
                "hint 应含已尽力指引: " + reason);
    }

    @Test
    @DisplayName("非 reviewer 的写草案不拦（planner 写 itinerary_draft 放行且不计数）")
    void testPlannerWriteNotIntercepted() {
        Recorder r = new Recorder();
        middleware.onActing(plannerAgent, ctx(), draftWrite(), r::pass);
        assertEquals("write_collab_file", r.last.get().toolCalls().get(0).getName(),
                "planner 写草案应放行");

        // reviewer 首修仍从 1 计（planner 没消耗计数）
        Recorder r2 = new Recorder();
        middleware.onActing(reviewerAgent, ctx(), draftWrite(), r2::pass);
        assertEquals("write_collab_file", r2.last.get().toolCalls().get(0).getName());
    }

    @Test
    @DisplayName("reviewer 写非草案文件（review_passed/report）不拦")
    void testReviewerNonDraftWriteNotIntercepted() {
        ActingInput reportWrite = new ActingInput(List.of(new ToolUseBlock("id-2",
                "write_collab_file", Map.of("file", "review_report.json", "contentJson", Map.of()))));
        Recorder r = new Recorder();
        middleware.onActing(reviewerAgent, ctx(), reportWrite, r::pass);
        assertEquals("write_collab_file", r.last.get().toolCalls().get(0).getName(),
                "写评审产物应放行");
    }

    @Test
    @DisplayName("review_passed.json 出现 → 计数重置（新一轮从第 1 次修订起）")
    void testPassedResetsCounter() {
        for (int i = 0; i < 2; i++) {
            middleware.onActing(reviewerAgent, ctx(), draftWrite(), input -> Flux.empty());
        }
        // 收敛通过：写 review_passed.json
        workspace.writeReviewPassed("u1", "conv-t",
                new com.travelscope.collab.ReviewPassed(88, 15, List.of("核验一致"), "2026-10-19 12:00:00"));

        Recorder r = new Recorder();
        middleware.onActing(reviewerAgent, ctx(), draftWrite(), r::pass);
        assertEquals("write_collab_file", r.last.get().toolCalls().get(0).getName(),
                "passed 后写草案应放行（计数已重置）");
    }

    @Test
    @DisplayName("会话隔离：不同会话修订计数互不影响")
    void testSessionIsolation() {
        for (int i = 0; i < 2; i++) {
            middleware.onActing(reviewerAgent, ctx(), draftWrite(), input -> Flux.empty());
        }
        RuntimeContext otherCtx = RuntimeContext.builder()
                .userId("u1").sessionId("conv-other").build();
        Recorder r = new Recorder();
        middleware.onActing(reviewerAgent, otherCtx, draftWrite(), r::pass);
        assertEquals("write_collab_file", r.last.get().toolCalls().get(0).getName(),
                "另一会话应独立计数放行");
    }

    // ==================== review-attempt 业务打点 ====================

    @Test
    @DisplayName("review-attempt 打点：修订记录 span（attempt=次数）")
    void testReviewAttemptSpan_revision() {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        when(tracer.nextSpan()).thenReturn(span);
        when(span.name(anyString())).thenReturn(span);
        ReviewerRetryMiddleware traced = new ReviewerRetryMiddleware(workspace, new TracingHelper(tracer));

        traced.onActing(reviewerAgent, ctx(), draftWrite(), input -> Flux.empty());

        verify(span).name("review-attempt");
        verify(span).tag("attempt", "1");
        verify(span).start();
        verify(span).end();
    }

    @Test
    @DisplayName("review-attempt 打点：带上上一轮 FAIL 的总分（result/score 解析自评审产物）")
    void testReviewAttemptSpan_retryWithScore() throws Exception {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        when(tracer.nextSpan()).thenReturn(span);
        when(span.name(anyString())).thenReturn(span);
        ReviewerRetryMiddleware traced = new ReviewerRetryMiddleware(workspace, new TracingHelper(tracer));

        // 上一轮评审产物：FAIL 总分 78
        workspace.writeReviewReport("u1", "conv-t",
                new com.travelscope.collab.ReviewReport(
                        List.of(new com.travelscope.collab.ReviewReport.ReviewSegment(3, false, List.of("超预算"))),
                        new com.travelscope.collab.ReviewReport.ReviewScores(18, 16, 12, 17, 15),
                        78, Map.of("3", List.of("换经济酒店")), "2026-10-19 12:00:00"));

        traced.onActing(reviewerAgent, ctx(), draftWrite(), input -> Flux.empty());

        verify(span).tag("attempt", "1");
        verify(span).tag("result", "FAIL");
        verify(span).tag("score", "78");
    }
}
