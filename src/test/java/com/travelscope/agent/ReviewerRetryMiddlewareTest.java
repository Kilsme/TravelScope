package com.travelscope.agent;

import com.travelscope.common.TracingHelper;
import com.travelscope.config.AgentConfig.TaskWorkspaceService;
import com.travelscope.config.AppProperties;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReviewerRetryMiddleware 保险丝单元测试（FR-S08 检测标准 4 的逻辑层验证）
 * <p>
 * Fake next 记录传入的 ActingInput（放行=原样 / 拦截=改写后），@TempDir 落盘
 * review_passed.md / review_report.md 模拟质检状态。
 * </p>
 */
class ReviewerRetryMiddlewareTest {

    @TempDir
    Path tempDir;

    private TaskWorkspaceService workspace;
    private ReviewerRetryMiddleware middleware;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getAgentscope().setWorkspacePath(tempDir.toString());
        workspace = new TaskWorkspaceService(props);
        middleware = new ReviewerRetryMiddleware(workspace);
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

    /** 构造 spawn reviewer 的 ActingInput */
    private static ActingInput reviewerSpawn() {
        return new ActingInput(List.of(new ToolUseBlock("id-1", "agent_spawn",
                Map.of("agent_id", ReviewerAgent.AGENT_NAME, "task", "质检送审"))));
    }

    private void writeReviewPassed() throws Exception {
        Path dir = workspace.getTaskDir("u1", "conv-t");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(TaskWorkspaceService.FILE_REVIEW_PASSED),
                "# 质检通过\n- 总分: 88/100");
    }

    @Test
    @DisplayName("首审与第 2/3 次送审放行（首审+2 回炉=3 次上限内）")
    void testFirstThreeAttemptsPassThrough() {
        for (int i = 1; i <= 3; i++) {
            Recorder r = new Recorder();
            middleware.onActing(null, ctx(), reviewerSpawn(), r::pass);
            assertEquals("agent_spawn", r.last.get().toolCalls().get(0).getName(),
                    "第 " + i + " 次送审应放行");
        }
    }

    @Test
    @DisplayName("检测标准4：第 4 次送审被保险丝拦截，改写为 review_retry_hint")
    void testFourthAttemptIntercepted() {
        for (int i = 0; i < 3; i++) {
            middleware.onActing(null, ctx(), reviewerSpawn(), input -> Flux.empty());
        }
        Recorder r = new Recorder();
        middleware.onActing(null, ctx(), reviewerSpawn(), r::pass);

        ToolUseBlock rewritten = r.last.get().toolCalls().get(0);
        assertEquals(ReviewerRetryMiddleware.RETRY_HINT_TOOL, rewritten.getName(),
                "第 4 次应被改写为 hint 工具");
        assertEquals("id-1", rewritten.getId(), "保持 tool_use id 配对");
        String reason = String.valueOf(rewritten.getInput().get("reason"));
        assertTrue(reason.contains("RETRY_LIMIT_REACHED") && reason.contains("已尽力"),
                "hint 应含已尽力指引: " + reason);
    }

    @Test
    @DisplayName("非 reviewer 的 spawn 不受影响（poi/route 等正常放行且不计数）")
    void testNonReviewerSpawnIgnored() {
        ActingInput poiSpawn = new ActingInput(List.of(
                new ToolUseBlock("id-2", "agent_spawn",
                        Map.of("agent_id", PoiResearchAgent.AGENT_NAME, "task", "检索"))));
        Recorder r = new Recorder();
        middleware.onActing(null, ctx(), poiSpawn, r::pass);
        assertEquals("agent_spawn", r.last.get().toolCalls().get(0).getName());

        // reviewer 首审仍从 1 计（poi 没消耗计数）
        Recorder r2 = new Recorder();
        middleware.onActing(null, ctx(), reviewerSpawn(), r2::pass);
        assertEquals("agent_spawn", r2.last.get().toolCalls().get(0).getName());
    }

    @Test
    @DisplayName("review_passed.md 出现 → 计数重置（新一轮质检从首审开始）")
    void testPassedResetsCounter() throws Exception {
        // 用完 3 次额度
        for (int i = 0; i < 3; i++) {
            middleware.onActing(null, ctx(), reviewerSpawn(), input -> Flux.empty());
        }
        writeReviewPassed();
        Recorder r = new Recorder();
        middleware.onActing(null, ctx(), reviewerSpawn(), r::pass);
        assertEquals("agent_spawn", r.last.get().toolCalls().get(0).getName(),
                "passed 后送审应放行（计数已重置）");
    }

    @Test
    @DisplayName("会话隔离：不同会话计数互不影响")
    void testSessionIsolation() {
        for (int i = 0; i < 3; i++) {
            middleware.onActing(null, ctx(), reviewerSpawn(), input -> Flux.empty());
        }
        RuntimeContext otherCtx = RuntimeContext.builder()
                .userId("u1").sessionId("conv-other").build();
        Recorder r = new Recorder();
        middleware.onActing(null, otherCtx, reviewerSpawn(), r::pass);
        assertEquals("agent_spawn", r.last.get().toolCalls().get(0).getName(),
                "另一会话应独立计数放行");
    }

    @Test
    @DisplayName("混合调用批次：同批含 poi spawn 与超限 reviewer spawn，只改写后者")
    void testMixedBatchSelectiveRewrite() {
        for (int i = 0; i < 3; i++) {
            middleware.onActing(null, ctx(), reviewerSpawn(), input -> Flux.empty());
        }
        ActingInput mixed = new ActingInput(List.of(
                new ToolUseBlock("id-p", "agent_spawn",
                        Map.of("agent_id", PoiResearchAgent.AGENT_NAME, "task", "检索")),
                new ToolUseBlock("id-r", "agent_spawn",
                        Map.of("agent_id", ReviewerAgent.AGENT_NAME, "task", "送审"))));
        Recorder r = new Recorder();
        middleware.onActing(null, ctx(), mixed, r::pass);

        assertEquals("agent_spawn", r.last.get().toolCalls().get(0).getName(), "poi spawn 保留");
        assertEquals(ReviewerRetryMiddleware.RETRY_HINT_TOOL,
                r.last.get().toolCalls().get(1).getName(), "reviewer spawn 被改写");
    }

    // ==================== review-attempt 业务打点（2026-10-02，mock Tracer 直证 span 内容） ====================

    @Test
    @DisplayName("review-attempt 打点：首审记录 span（仅 attempt，无产物时不打 result/score）")
    void testReviewAttemptSpan_firstAttempt() {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        when(tracer.nextSpan()).thenReturn(span);
        when(span.name(anyString())).thenReturn(span);
        ReviewerRetryMiddleware traced = new ReviewerRetryMiddleware(workspace, new TracingHelper(tracer));

        traced.onActing(null, ctx(), reviewerSpawn(), input -> Flux.empty());

        verify(span).name("review-attempt");
        verify(span).tag("attempt", "1");
        verify(span, never()).tag(eq("result"), anyString());
        verify(span, never()).tag(eq("score"), anyString());
        verify(span).start();
        verify(span).end();
    }

    @Test
    @DisplayName("review-attempt 打点：回炉送审带上上一轮 FAIL 的总分（result/score 解析自评审产物）")
    void testReviewAttemptSpan_retryWithScore() throws Exception {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        when(tracer.nextSpan()).thenReturn(span);
        when(span.name(anyString())).thenReturn(span);
        ReviewerRetryMiddleware traced = new ReviewerRetryMiddleware(workspace, new TracingHelper(tracer));

        // 首审（放行）
        traced.onActing(null, ctx(), reviewerSpawn(), input -> Flux.empty());
        // 首审产物：FAIL 总分 78（REVIEW_RESULT 首行的文件等价物）
        Path dir = workspace.getTaskDir("u1", "conv-t");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(TaskWorkspaceService.FILE_REVIEW_REPORT),
                "# 质检报告：不通过\n\n- 总分: 78/100（通过线 80）\n\n| 维度 | 得分 |\n|---|---|");

        // 回炉重送 → span 带 attempt=2 + 上一轮 result=FAIL + score=78
        traced.onActing(null, ctx(), reviewerSpawn(), input -> Flux.empty());

        verify(span, times(2)).start();
        verify(span, times(2)).end();
        verify(span).tag("attempt", "1");
        verify(span).tag("attempt", "2");
        verify(span).tag("result", "FAIL");
        verify(span).tag("score", "78");
    }
}
