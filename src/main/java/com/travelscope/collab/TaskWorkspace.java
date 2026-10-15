package com.travelscope.collab;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 会话协作工作区（S0.5：自 {@code AgentConfig} 内嵌类 TaskWorkspaceService 迁出，
 * MD 散文契约改为 JSON 契约）。
 * <p>
 * 路径规则不变：{@code {workspaceRoot}/{userId}/tasks/{sessionId}/}（与 harness 用户目录
 * 约定对齐，Agent 侧相对路径 {@code tasks/{sessionId}/...}）。全部读写委托
 * {@link CollabFileWriter} / {@link CollabFileReader}——写入必经 Schema 校验 + 原子落盘，
 * 读取必经校验（坏文件抛异常不静默降级）。
 * </p>
 * <p>
 * 过渡期设计（S2.7 / S3.1 切干净前）：reviewer 仍产出 review_passed.md / review_report.md，
 * planner 仍产出 itinerary_draft.md / execution_result.md——ReviewerRetryMiddleware 与
 * ChatService 的兜底读取继续用 {@code *_MD} 常量；JSON 类型化方法对旧 MD 残留
 * 「存在即忽略」（log.debug，不做迁移，v3 生产残留无消费价值）。
 * </p>
 */
@Service
public class TaskWorkspace {

    private static final Logger log = LoggerFactory.getLogger(TaskWorkspace.class);

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ==================== v4 JSON 契约文件名（类型化方法使用） ====================

    public static final String FILE_INTAKE_RESULT = "intake_result.json";
    public static final String FILE_TASK_BACKLOG = "task_backlog.json";
    public static final String FILE_POI_SHORTLIST = "poi_shortlist.json";
    public static final String FILE_ROUTE_PLAN = "route_plan.json";
    public static final String FILE_EXECUTION_RESULT = "execution_result.json";
    public static final String FILE_ITINERARY_DRAFT = "itinerary_draft.json";
    public static final String FILE_REVIEW_REPORT = "review_report.json";
    public static final String FILE_REVIEW_PASSED = "review_passed.json";
    public static final String FILE_SESSION_META = "session_meta.json";

    // ==================== v3 MD 过渡常量（S2.7 / S3.1 切 JSON 后删除） ====================

    /** @deprecated 过渡：reviewer 切 JSON 产物（S2.7）后删除 */
    @Deprecated
    public static final String FILE_REVIEW_PASSED_MD = "review_passed.md";
    /** @deprecated 过渡：reviewer 切 JSON 产物（S2.7）后删除 */
    @Deprecated
    public static final String FILE_REVIEW_REPORT_MD = "review_report.md";
    /** @deprecated 过渡：ChatService 兜底整合切 JSON（S3.1）后删除 */
    @Deprecated
    public static final String FILE_ITINERARY_DRAFT_MD = "itinerary_draft.md";
    /** @deprecated 过渡：ChatService 兜底整合切 JSON（S3.1）后删除 */
    @Deprecated
    public static final String FILE_EXECUTION_RESULT_MD = "execution_result.md";
    /** @deprecated 过渡：EvalCase 产物断言读 v3 MD 产物（P3 切 JSON 后删除） */
    @Deprecated
    public static final String FILE_POI_SHORTLIST_MD = "poi_shortlist.md";
    /** @deprecated 过渡：EvalCase 产物断言读 v3 MD 产物（P3 切 JSON 后删除） */
    @Deprecated
    public static final String FILE_ROUTE_PLAN_MD = "route_plan.md";

    /** v4 参与者名单（三 Agent 目标架构；P2 完成前实际仍含 v3 子 Agent，元信息以前瞻为准） */
    private static final List<String> PARTICIPANTS =
            List.of("travel-master", "itinerary-agent", "reviewer-agent");

    private final CollabFileWriter writer;
    private final CollabFileReader reader;

    public TaskWorkspace(CollabFileWriter writer, CollabFileReader reader) {
        this.writer = writer;
        this.reader = reader;
    }

    // ==================== 路径 ====================

    /** 会话协作目录：{workspaceRoot}/{userId}/tasks/{sessionId} */
    public Path getTaskDir(String userId, String sessionId) {
        return writer.resolveSessionDir(userId, sessionId);
    }

    /** 任务清单在 Agent 工作区中的相对路径（委派说明/路由指令用） */
    public String backlogRelativePath(String sessionId) {
        return "tasks/" + sessionId + "/" + FILE_TASK_BACKLOG;
    }

    /** 会话协作目录在 Agent 工作区中的相对路径 */
    public String collabDirRelativePath(String sessionId) {
        return "tasks/" + sessionId;
    }

    // ==================== 会话元信息 ====================

    /** 初始化任务区间（建目录 + 写 session_meta.json） */
    public Path initTaskWorkspace(String userId, String sessionId) {
        Path dir = getTaskDir(userId, sessionId);
        SessionMeta meta = new SessionMeta(String.valueOf(userId), sessionId,
                now(), "进行中", PARTICIPANTS, null);
        writer.write(dir, FILE_SESSION_META, meta);
        log.info("任务区间初始化: 用户={}, 会话={}, dir={}", userId, sessionId, dir);
        return dir;
    }

    public Optional<SessionMeta> readSessionMeta(String userId, String sessionId) {
        return readJson(userId, sessionId, FILE_SESSION_META, "session_meta.md", SessionMeta.class);
    }

    /** 更新会话状态（读改写；meta 不存在时沉默跳过，与 v3 语义一致） */
    public void updateSessionStatus(String userId, String sessionId, String status) {
        Path dir = getTaskDir(userId, sessionId);
        reader.read(dir, FILE_SESSION_META, SessionMeta.class).ifPresent(meta ->
                writer.write(dir, FILE_SESSION_META, new SessionMeta(meta.userId(), meta.sessionId(),
                        meta.createdAt(), status, meta.participants(), now())));
    }

    // ==================== 类型化读写（9 个契约文件） ====================

    public void writeIntakeResult(String userId, String sessionId, IntakeResult value) {
        writer.write(getTaskDir(userId, sessionId), FILE_INTAKE_RESULT, value);
    }

    public Optional<IntakeResult> readIntakeResult(String userId, String sessionId) {
        return readJson(userId, sessionId, FILE_INTAKE_RESULT, "intake_done.md", IntakeResult.class);
    }

    public void writeTaskBacklog(String userId, String sessionId, TaskBacklog value) {
        writer.write(getTaskDir(userId, sessionId), FILE_TASK_BACKLOG, value);
    }

    public Optional<TaskBacklog> readTaskBacklog(String userId, String sessionId) {
        return readJson(userId, sessionId, FILE_TASK_BACKLOG, "task_backlog.md", TaskBacklog.class);
    }

    public void writePoiShortlist(String userId, String sessionId, PoiShortlist value) {
        writer.write(getTaskDir(userId, sessionId), FILE_POI_SHORTLIST, value);
    }

    public Optional<PoiShortlist> readPoiShortlist(String userId, String sessionId) {
        return readJson(userId, sessionId, FILE_POI_SHORTLIST, "poi_shortlist.md", PoiShortlist.class);
    }

    public void writeRoutePlan(String userId, String sessionId, RoutePlan value) {
        writer.write(getTaskDir(userId, sessionId), FILE_ROUTE_PLAN, value);
    }

    public Optional<RoutePlan> readRoutePlan(String userId, String sessionId) {
        return readJson(userId, sessionId, FILE_ROUTE_PLAN, "route_plan.md", RoutePlan.class);
    }

    public Optional<ExecutionResult> readExecutionResult(String userId, String sessionId) {
        return readJson(userId, sessionId, FILE_EXECUTION_RESULT, "execution_result.md", ExecutionResult.class);
    }

    /**
     * 追加一条任务执行结果（读-改-写；Agent 执行单线程串行，无并发竞争）。
     * 替代 v3 的文本追加（appendExecutionResult）。
     */
    public void appendExecutionResult(String userId, String sessionId, ExecutionResult.TaskResultItem item) {
        Path dir = getTaskDir(userId, sessionId);
        ExecutionResult existing = reader.read(dir, FILE_EXECUTION_RESULT, ExecutionResult.class)
                .orElse(new ExecutionResult(sessionId, List.of()));
        List<ExecutionResult.TaskResultItem> merged = new ArrayList<>(existing.results());
        merged.add(item);
        writer.write(dir, FILE_EXECUTION_RESULT,
                new ExecutionResult(sessionId, List.copyOf(merged)));
        log.info("执行结果已追加: 用户={}, 会话={}, taskId={}", userId, sessionId, item.taskId());
    }

    public void writeItineraryDraft(String userId, String sessionId, ItineraryDraft value) {
        writer.write(getTaskDir(userId, sessionId), FILE_ITINERARY_DRAFT, value);
        updateSessionStatus(userId, sessionId, "已完成");
        log.info("行程草案已写入: 用户={}, 会话={}", userId, sessionId);
    }

    public Optional<ItineraryDraft> readItineraryDraft(String userId, String sessionId) {
        return readJson(userId, sessionId, FILE_ITINERARY_DRAFT, "itinerary_draft.md", ItineraryDraft.class);
    }

    public void writeReviewReport(String userId, String sessionId, ReviewReport value) {
        writer.write(getTaskDir(userId, sessionId), FILE_REVIEW_REPORT, value);
    }

    public Optional<ReviewReport> readReviewReport(String userId, String sessionId) {
        return readJson(userId, sessionId, FILE_REVIEW_REPORT, "review_report.md", ReviewReport.class);
    }

    public void writeReviewPassed(String userId, String sessionId, ReviewPassed value) {
        writer.write(getTaskDir(userId, sessionId), FILE_REVIEW_PASSED, value);
    }

    public Optional<ReviewPassed> readReviewPassed(String userId, String sessionId) {
        return readJson(userId, sessionId, FILE_REVIEW_PASSED, "review_passed.md", ReviewPassed.class);
    }

    // ==================== 内部 ====================

    /**
     * JSON 类型化读取 + 旧 MD 兼容：JSON 缺失且同名 .md 存在 → log.debug「旧格式忽略」，
     * 返回 empty（v3 残留不迁移）。
     */
    private <T> Optional<T> readJson(String userId, String sessionId,
                                     String jsonName, String legacyMdName, Class<T> type) {
        Path dir = getTaskDir(userId, sessionId);
        Optional<T> result = reader.read(dir, jsonName, type);
        if (result.isEmpty() && legacyMdName != null && Files.exists(dir.resolve(legacyMdName))) {
            log.debug("旧格式 MD 忽略（v3 残留，不迁移）: {}", dir.resolve(legacyMdName));
        }
        return result;
    }

    private static String now() {
        return LocalDateTime.now().format(FMT);
    }
}
