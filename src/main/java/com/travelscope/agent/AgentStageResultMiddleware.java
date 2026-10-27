package com.travelscope.agent;

import com.travelscope.collab.AgentStageResult;
import com.travelscope.collab.TaskWorkspace;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;

/** 确定性记录 planner/reviewer 阶段终态，并在 reviewer 启动前校验 planner 终态。 */
public final class AgentStageResultMiddleware implements MiddlewareBase {
    private static final Logger log = LoggerFactory.getLogger(AgentStageResultMiddleware.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TaskWorkspace workspace;
    private final AgentStageResult.Stage stage;

    public AgentStageResultMiddleware(TaskWorkspace workspace, AgentStageResult.Stage stage) {
        this.workspace = workspace;
        this.stage = stage;
    }

    @Override
    public int order() {
        return -850;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        String userId = ctx.getUserId();
        String sessionId = ctx.getSessionId();
        if (userId == null || sessionId == null) {
            return next.apply(input);
        }
        if (stage == AgentStageResult.Stage.REVIEWER && !plannerReady(userId, sessionId)) {
            write(userId, sessionId, AgentStageResult.Status.NOT_READY,
                    "PLANNER_NOT_DONE", false, List.of());
            log.warn("reviewer 未启动：planner 终态或草案未就绪，用户={}, 会话={}", userId, sessionId);
            return Flux.empty();
        }
        return next.apply(input)
                .doOnComplete(() -> writeCompletion(userId, sessionId))
                .doOnError(error -> {
                    write(userId, sessionId, AgentStageResult.Status.FAILED,
                            stage + "_EXECUTION_FAILED", true, List.of());
                    log.warn("{} 阶段执行失败，已写终态: 用户={}, 会话={}, 原因={}",
                            stage, userId, sessionId, error.getMessage());
                });
    }

    private boolean plannerReady(String userId, String sessionId) {
        return workspace.readAgentStageResult(userId, sessionId, AgentStageResult.Stage.PLANNER)
                .filter(result -> result.status() == AgentStageResult.Status.DONE)
                .filter(result -> result.artifacts().contains(TaskWorkspace.FILE_ITINERARY_DRAFT))
                .isPresent()
                && workspace.readItineraryDraft(userId, sessionId).isPresent();
    }

    private void writeCompletion(String userId, String sessionId) {
        if (stage == AgentStageResult.Stage.PLANNER) {
            if (workspace.readItineraryDraft(userId, sessionId).isPresent()) {
                write(userId, sessionId, AgentStageResult.Status.DONE,
                        null, false, List.of(TaskWorkspace.FILE_ITINERARY_DRAFT));
            } else {
                write(userId, sessionId, AgentStageResult.Status.FAILED,
                        "DRAFT_MISSING", false, List.of());
            }
            return;
        }
        if (workspace.readReviewPassed(userId, sessionId).isPresent()) {
            write(userId, sessionId, AgentStageResult.Status.DONE,
                    null, false, List.of(TaskWorkspace.FILE_REVIEW_PASSED));
        } else if (workspace.readReviewReport(userId, sessionId).isPresent()) {
            write(userId, sessionId, AgentStageResult.Status.DONE,
                    null, false, List.of(TaskWorkspace.FILE_REVIEW_REPORT));
        } else {
            write(userId, sessionId, AgentStageResult.Status.FAILED,
                    "REVIEW_ARTIFACT_MISSING", false, List.of());
        }
    }

    private void write(String userId, String sessionId, AgentStageResult.Status status,
                       String reasonCode, boolean retryable, List<String> artifacts) {
        workspace.writeAgentStageResult(userId, sessionId,
                new AgentStageResult(stage, status, reasonCode, retryable,
                        artifacts, LocalDateTime.now().format(FMT)));
    }
}
