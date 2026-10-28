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
 * 会话协作工作区，路径为 {@code {workspaceRoot}/{userId}/tasks/{sessionId}/}；Agent 使用
 * 相对路径 {@code tasks/{sessionId}/...}。当前协作契约全部为 JSON，写入执行 Schema
 * 校验和原子替换，读取时同样校验。旧 MD 文件只用于存量会话兼容，不会自动迁移。
 */
@Service
public class TaskWorkspace {

    private static final Logger log = LoggerFactory.getLogger(TaskWorkspace.class);

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ==================== JSON 契约文件名 ====================

    public static final String FILE_INTAKE_RESULT = "intake_result.json";
    public static final String FILE_TASK_BACKLOG = "task_backlog.json";
    public static final String FILE_POI_SHORTLIST = "poi_shortlist.json";
    public static final String FILE_ROUTE_PLAN = "route_plan.json";
    public static final String FILE_TRANSPORT_SELECTION = "transport_selection.json";
    public static final String FILE_HOTEL_SELECTION = "hotel_selection.json";
    public static final String FILE_EXECUTION_RESULT = "execution_result.json";
    public static final String FILE_ITINERARY_DRAFT = "itinerary_draft.json";
    public static final String FILE_REVIEW_REPORT = "review_report.json";
    public static final String FILE_REVIEW_PASSED = "review_passed.json";
    public static final String FILE_PLANNER_STAGE_RESULT = "planner_stage_result.json";
    public static final String FILE_REVIEWER_STAGE_RESULT = "reviewer_stage_result.json";
    public static final String FILE_SESSION_META = "session_meta.json";

    // ==================== 旧 MD 会话兼容 ====================

    /** @deprecated 仅用于读取旧会话；新产物必须使用 JSON 契约。 */
    @Deprecated
    public static final String FILE_REVIEW_PASSED_MD = "review_passed.md";
    /** @deprecated 仅用于读取旧会话；新产物必须使用 JSON 契约。 */
    @Deprecated
    public static final String FILE_REVIEW_REPORT_MD = "review_report.md";
    /** @deprecated 仅用于读取旧会话；新产物必须使用 JSON 契约。 */
    @Deprecated
    public static final String FILE_ITINERARY_DRAFT_MD = "itinerary_draft.md";
    /** @deprecated 仅用于读取旧会话；新产物必须使用 JSON 契约。 */
    @Deprecated
    public static final String FILE_EXECUTION_RESULT_MD = "execution_result.md";

    /** 当前会话元信息中的参与者名单。 */
    private static final List<String> PARTICIPANTS =
            List.of("travel-master", "planning-agent", "reviewer-agent");

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

    /** 更新会话状态；元信息不存在时不执行写入。 */
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

    public void writeTransportSelection(String userId, String sessionId, TransportSelection value) {
        writer.write(getTaskDir(userId, sessionId), FILE_TRANSPORT_SELECTION, value);
    }

    public Optional<TransportSelection> readTransportSelection(String userId, String sessionId) {
        return reader.read(getTaskDir(userId, sessionId), FILE_TRANSPORT_SELECTION, TransportSelection.class);
    }

    public void writeHotelSelection(String userId, String sessionId, HotelSelection value) {
        writer.write(getTaskDir(userId, sessionId), FILE_HOTEL_SELECTION, value);
    }

    public Optional<HotelSelection> readHotelSelection(String userId, String sessionId) {
        return reader.read(getTaskDir(userId, sessionId), FILE_HOTEL_SELECTION, HotelSelection.class);
    }

    public Optional<ExecutionResult> readExecutionResult(String userId, String sessionId) {
        return readJson(userId, sessionId, FILE_EXECUTION_RESULT, "execution_result.md", ExecutionResult.class);
    }

    /** 追加一条任务执行结果（读-改-写；同一 Agent 会话内串行执行）。 */
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

    public void writeAgentStageResult(String userId, String sessionId, AgentStageResult value) {
        writer.write(getTaskDir(userId, sessionId), stageResultFile(value.stage()), value);
    }

    public Optional<AgentStageResult> readAgentStageResult(
            String userId, String sessionId, AgentStageResult.Stage stage) {
        return readJson(userId, sessionId, stageResultFile(stage), null, AgentStageResult.class)
                .filter(result -> result.stage() == stage);
    }

    public void deleteAgentStageResults(String userId, String sessionId) {
        Path dir = getTaskDir(userId, sessionId);
        writer.delete(dir, FILE_PLANNER_STAGE_RESULT);
        writer.delete(dir, FILE_REVIEWER_STAGE_RESULT);
    }

    private static String stageResultFile(AgentStageResult.Stage stage) {
        return stage == AgentStageResult.Stage.PLANNER
                ? FILE_PLANNER_STAGE_RESULT : FILE_REVIEWER_STAGE_RESULT;
    }

    /**
     * 删除两个 review 产物（review_passed.json / review_report.json），不存在静默跳过。
     * 新一轮规划编排启动时调用：review_passed 有粘滞语义，跨轮残留会顶包新一轮规则质检。
     */
    public void deleteReviewArtifacts(String userId, String sessionId) {
        Path dir = getTaskDir(userId, sessionId);
        writer.delete(dir, FILE_REVIEW_PASSED);
        writer.delete(dir, FILE_REVIEW_REPORT);
    }

    // ==================== 内部 ====================

    /** 类型化读取 JSON；仅记录同名旧 MD 的存在，不解析或迁移旧内容。 */
    private <T> Optional<T> readJson(String userId, String sessionId,
                                     String jsonName, String legacyMdName, Class<T> type) {
        Path dir = getTaskDir(userId, sessionId);
        Optional<T> result = reader.read(dir, jsonName, type);
        if (result.isEmpty() && legacyMdName != null && Files.exists(dir.resolve(legacyMdName))) {
            log.debug("检测到旧 MD 产物，类型化读取不解析: {}", dir.resolve(legacyMdName));
        }
        return result;
    }

    private static String now() {
        return LocalDateTime.now().format(FMT);
    }
}
