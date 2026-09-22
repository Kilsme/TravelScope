package com.travelscope.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.travelscope.config.AgentConfig.TaskWorkspaceService;
import com.travelscope.dto.PlanningTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 任务容器注册表（需求 2：代码层强制的任务容器；需求 1：用户 + 会话双级隔离）
 * <p>
 * 每个用户会话对应一个 {@link SessionTaskContainer}，注册键为 {@code userId:sessionId}，
 * 不同用户、同一用户的不同会话互相隔离。容器内含双份数据：
 * <ul>
 *   <li><b>内存任务队列</b>：{@code List<TaskRecord>}（带状态），供进度查询与状态更新</li>
 *   <li><b>MD 文件</b>：{@code {workspace}/{userId}/tasks/{sessionId}/task_backlog.md}，
 *       与 Agent 工作区的相对路径 {@code tasks/{sessionId}/task_backlog.md} 对齐，
 *       子 Agent 通过 read_file 按路径读取主 Agent 下发的任务</li>
 * </ul>
 * 主 Agent 通过 {@code get_task_progress} 工具查询未完成任务数，子 Agent 通过
 * {@code update_task_status} 工具逐项回报状态，两者都最终落到本容器。
 * </p>
 */
@Service
public class TaskRegistry {

    private static final Logger log = LoggerFactory.getLogger(TaskRegistry.class);

    /** 任务状态 */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    /**
     * 四维覆盖规则（FR-S03：交通/住宿/景点/天气，缺维报错重拆）
     * <p>
     * 双路匹配：suggestedTool 前缀/精确 + description 关键词兜底——
     * 模型拆分时两项都可能携带维度信号，任一命中即视为该维覆盖。
     * </p>
     */
    private static final List<DimensionRule> DIMENSION_RULES = List.of(
            new DimensionRule("交通", List.of("train-ticket-query", "flight-ticket-query", "mcp__c12306", "mcp__variflight"),
                    List.of("火车", "高铁", "动车", "车票", "机票", "航班", "交通", "接驳", "大交通")),
            new DimensionRule("住宿", List.of("hotel-search"),
                    List.of("酒店", "住宿", "民宿", "宾馆", "青旅", "入住", "住哪", "住哪里", "落脚")),
            new DimensionRule("景点", List.of("attraction-search", "searchPois"),
                    List.of("景点", "POI", "游览", "景区", "博物馆", "打卡", "行程安排")),
            new DimensionRule("天气", List.of("weather-query"),
                    List.of("天气", "气温", "降水", "穿衣", "预报")));

    private final TaskWorkspaceService taskWorkspaceService;

    /** 注册键 userId:sessionId → 会话任务容器 */
    private final Map<String, SessionTaskContainer> containers = new ConcurrentHashMap<>();

    public TaskRegistry(TaskWorkspaceService taskWorkspaceService) {
        this.taskWorkspaceService = taskWorkspaceService;
    }

    /**
     * 登记任务清单（create_task_backlog 工具的唯一实现）：
     * 解析任务 JSON → <b>四维覆盖校验</b>（FR-S03：交通/住宿/景点/天气缺维报错重拆，
     * 校验失败不落盘不注册）→ 落盘 MD（用户+会话隔离路径）→ 注册内存容器
     *
     * @return 登记结果描述（供 Agent 工具返回；缺维时返回 ERROR 引导补拆）
     */
    public String createBacklog(String userId, String sessionId, String tasksJson) {
        List<PlanningTask> parsed = parseTasks(tasksJson);
        if (parsed.isEmpty()) {
            return "ERROR: tasksJson 无法解析为任务数组或为空，请传 JSON 数组，"
                    + "每项形如 {\"taskId\":\"T1\",\"description\":\"...\",\"suggestedTool\":\"...\",\"priority\":\"P0\"}";
        }
        List<String> missingDimensions = missingDimensions(parsed);
        if (!missingDimensions.isEmpty()) {
            log.warn("四维校验未通过: 用户={}, 会话={}, 缺少维度={}", userId, sessionId, missingDimensions);
            return "ERROR: 任务清单缺少维度：" + String.join("、", missingDimensions)
                    + "。行程规划必须四维覆盖（交通/住宿/景点/天气，每维至少一项任务）。"
                    + "请补齐缺失维度的任务后重新调用 create_task_backlog（本次登记未生效）。"
                    + dimensionHints(missingDimensions);
        }
        String md = renderBacklogMd(sessionId, parsed);
        Path file = taskWorkspaceService.writeTaskBacklog(userId, sessionId, md);
        SessionTaskContainer container = new SessionTaskContainer(userId, sessionId, file, parsed);
        containers.put(containerKey(userId, sessionId), container);
        log.info("任务清单已登记: {} 项任务, 四维校验通过, 用户={}, 会话={}, 文件={}",
                parsed.size(), userId, sessionId, file);
        return "任务清单已登记：共 " + parsed.size() + " 项任务（"
                + parsed.stream().map(t -> nz(t.taskId)).collect(Collectors.joining("、"))
                + "），已写入 " + taskWorkspaceService.backlogRelativePath(sessionId)
                + "。现在可以调用 agent_spawn 委派 planning-agent，"
                + "并在任务说明中告知其用 read_file 读取该路径执行；执行期间可随时调用 get_task_progress 查询进度。";
    }

    /**
     * 四维覆盖校验：返回缺失的维度名（空列表 = 通过）。
     * 双路匹配：suggestedTool 前缀/精确命中 或 description 含关键词，任一即覆盖。
     */
    private List<String> missingDimensions(List<PlanningTask> tasks) {
        List<String> missing = new ArrayList<>();
        for (DimensionRule rule : DIMENSION_RULES) {
            boolean covered = tasks.stream().anyMatch(t -> {
                String tool = t.suggestedTool == null ? "" : t.suggestedTool;
                String desc = t.description == null ? "" : t.description;
                boolean toolHit = rule.tools().stream().anyMatch(tool::startsWith);
                boolean descHit = rule.keywords().stream().anyMatch(desc::contains);
                return toolHit || descHit;
            });
            if (!covered) {
                missing.add(rule.name());
            }
        }
        return missing;
    }

    /** 缺维时的补拆提示（给模型的建议技能名） */
    private String dimensionHints(List<String> missing) {
        StringBuilder sb = new StringBuilder("维度参考：");
        for (DimensionRule rule : DIMENSION_RULES) {
            if (missing.contains(rule.name())) {
                sb.append("「").append(rule.name()).append("」建议 suggestedTool=").append(rule.tools().get(0)).append("；");
            }
        }
        return sb.toString();
    }

    /** 单个维度的覆盖规则（技能名前缀 + 描述关键词） */
    private record DimensionRule(String name, List<String> tools, List<String> keywords) {
    }

    /**
     * 查询任务进度（get_task_progress 工具的实现）
     */
    public String progress(String userId, String sessionId) {
        SessionTaskContainer c = containers.get(containerKey(userId, sessionId));
        if (c == null) {
            return "ERROR: 会话 " + sessionId + " 尚未登记任务清单，请先调用 create_task_backlog";
        }
        return c.progressSummary();
    }

    /**
     * 更新任务状态（update_task_status 工具的实现，供子 Agent 逐项回报）
     */
    public String updateStatus(String userId, String sessionId, String taskId, String status) {
        SessionTaskContainer c = containers.get(containerKey(userId, sessionId));
        if (c == null) {
            return "ERROR: 会话 " + sessionId + " 尚未登记任务清单";
        }
        return c.updateStatus(taskId, status);
    }

    /**
     * 该会话是否已登记任务清单（委派门禁 PlanningGateMiddleware 使用）
     */
    public boolean hasBacklog(String userId, String sessionId) {
        SessionTaskContainer c = containers.get(containerKey(userId, sessionId));
        return c != null && !c.tasks.isEmpty();
    }

    /**
     * 委派门禁的纠正提示（agent_spawn 被拦截时返回给主 Agent 的工具结果）
     */
    public String gateHint(String userId, String sessionId) {
        return "GATE_REJECTED: 本会话(" + sessionId + ")尚未登记任务清单，agent_spawn 已被系统拦截未执行。"
                + "请先调用 create_task_backlog 工具（sessionId=" + sessionId
                + "）把拆分好的任务清单登记进任务容器，拿到登记成功结果后再调用 agent_spawn 委派 planning-agent。"
                + "禁止用 write_file 代替 create_task_backlog。";
    }

    // ==================== 内部实现 ====================

    private static String containerKey(String userId, String sessionId) {
        return userId + ":" + sessionId;
    }

    private List<PlanningTask> parseTasks(String tasksJson) {
        try {
            JSONArray arr = JSON.parseArray(tasksJson);
            if (arr == null) {
                return List.of();
            }
            return arr.stream().map(o -> {
                JSONObject jo = (JSONObject) o;
                PlanningTask t = new PlanningTask();
                t.taskId = jo.getString("taskId");
                t.description = jo.getString("description");
                t.suggestedTool = jo.getString("suggestedTool");
                t.priority = jo.getString("priority");
                return t;
            }).toList();
        } catch (Exception e) {
            log.warn("tasksJson 解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String renderBacklogMd(String sessionId, List<PlanningTask> tasks) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 任务清单\n\n")
                .append("- 会话: ").append(sessionId).append('\n')
                .append("- 状态: 待执行\n\n")
                .append("| 任务ID | 描述 | 建议工具/技能 | 优先级 | 状态 |\n")
                .append("|---|---|---|---|---|\n");
        for (PlanningTask t : tasks) {
            sb.append("| ").append(nz(t.taskId)).append(" | ")
                    .append(nz(t.description)).append(" | ")
                    .append(nz(t.suggestedTool)).append(" | ")
                    .append(nz(t.priority)).append(" | ")
                    .append(STATUS_PENDING).append(" |\n");
        }
        sb.append("\n> 本清单由主 Agent 通过 create_task_backlog 工具登记到任务容器，执行 Agent 按该文件内容执行，\n")
                .append("> 并通过 update_task_status 工具逐项回报状态。\n");
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s.replace('|', '/');
    }

    /**
     * 单个会话的任务容器（内存队列 + 状态）
     */
    public static class SessionTaskContainer {

        /** 单条任务记录（容器内的运行时状态） */
        public static class TaskRecord {
            public final PlanningTask task;
            public final AtomicReference<String> status = new AtomicReference<>(STATUS_PENDING);

            TaskRecord(PlanningTask task) {
                this.task = task;
            }
        }

        private final String userId;
        private final String sessionId;
        private final Path backlogFile;
        private final List<TaskRecord> tasks;

        SessionTaskContainer(String userId, String sessionId, Path backlogFile, List<PlanningTask> parsed) {
            this.userId = userId;
            this.sessionId = sessionId;
            this.backlogFile = backlogFile;
            this.tasks = parsed.stream().map(TaskRecord::new).collect(Collectors.toUnmodifiableList());
        }

        public String updateStatus(String taskId, String status) {
            String normalized = status == null ? "" : status.trim().toUpperCase();
            if (!List.of(STATUS_PENDING, STATUS_IN_PROGRESS, STATUS_DONE, STATUS_FAILED).contains(normalized)) {
                return "ERROR: 非法状态 " + status + "，允许值: PENDING/IN_PROGRESS/DONE/FAILED";
            }
            for (TaskRecord r : tasks) {
                if (r.task.taskId != null && r.task.taskId.equalsIgnoreCase(taskId)) {
                    r.status.set(normalized);
                    appendStatusToMd(r);
                    log.info("任务状态更新: 会话={}, 任务={}, 状态={}", sessionId, taskId, normalized);
                    return "已更新 " + taskId + " → " + normalized + "。" + progressSummary();
                }
            }
            return "ERROR: 未找到任务 " + taskId + "，当前清单: "
                    + tasks.stream().map(t -> t.task.taskId).collect(Collectors.joining("、"));
        }

        public String progressSummary() {
            long done = countByStatus(STATUS_DONE);
            long failed = countByStatus(STATUS_FAILED);
            long inProgress = countByStatus(STATUS_IN_PROGRESS);
            long pending = tasks.size() - done - failed - inProgress;
            String unfinished = tasks.stream()
                    .filter(r -> !STATUS_DONE.equals(r.status.get()))
                    .map(r -> nz(r.task.taskId) + "(" + r.status.get() + ")")
                    .collect(Collectors.joining("、"));
            return "共 " + tasks.size() + " 项任务：已完成 " + done + "，进行中 " + inProgress
                    + "，待处理 " + pending + "，失败 " + failed
                    + (unfinished.isEmpty() ? "。全部任务已完成。" : "。未完成任务: " + unfinished);
        }

        private long countByStatus(String status) {
            return tasks.stream().filter(r -> status.equals(r.status.get())).count();
        }

        /**
         * 状态变更同步追加到 MD 文件（保持磁盘容器与内存一致）
         */
        private void appendStatusToMd(TaskRecord r) {
            try {
                String line = "\n- [" + java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        + "] " + nz(r.task.taskId) + " → " + r.status.get();
                Files.writeString(backlogFile, line, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception e) {
                log.warn("任务状态写入 MD 失败（不影响内存容器）: {}", e.getMessage());
            }
        }

        public List<TaskRecord> snapshot() {
            return Collections.unmodifiableList(tasks);
        }
    }
}
