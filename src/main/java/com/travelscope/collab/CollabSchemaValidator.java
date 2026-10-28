package com.travelscope.collab;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 工作区契约 Schema 校验器（S0.2）。
 * <p>
 * 校验三段式：
 * <ol>
 *   <li>版本预检：根节点必须是 JSON 对象且 {@code schemaVersion == 4}，
 *       缺失/不等给出「版本不符」明确消息（早于 DTO 构造器，消息不含糊）</li>
 *   <li>fastjson2 反序列化：语法错误、字段缺失（requireNonNull）、单字段边界越界
 *       （DTO 紧凑构造器抛出）统一包成 {@link CollabValidationException}，
 *       消息带原文前 200 字符与根因</li>
 *   <li>类型级跨字段规则：TaskBacklog 的 taskId 非空白且不重复、
 *       ReviewReport 的 total 与 5 维之和一致（±1 容差）、
 *       RoutePlan 每日 commuteMinutesTotal 不超过当日各段之和（+1 分钟容差）、
 *       IntakeResult 的 status 合法枚举</li>
 * </ol>
 * 规则失败消息一律含 {@code [类型名] 字段路径 原因}，供日志快速定位（conv-97 事故教训）。
 * </p>
 */
public final class CollabSchemaValidator {

    /** 期望的契约版本（与 9 个契约 DTO 的 SCHEMA_VERSION 一致） */
    public static final int EXPECTED_SCHEMA_VERSION = 4;

    /** 异常消息中携带的原文片段长度上限 */
    private static final int RAW_SNIPPET_LIMIT = 200;

    /** ReviewReport 总分与 5 维之和的容差（评分偶有 ±1 取整误差） */
    private static final int REVIEW_TOTAL_TOLERANCE = 1;

    /** RoutePlan 每日总通勤与各段之和的容差（分钟，取整误差） */
    private static final int COMMUTE_TOTAL_TOLERANCE_MIN = 1;

    private CollabSchemaValidator() {
    }

    /**
     * 校验并返回 DTO：版本预检 → 反序列化 → 类型级规则。
     *
     * @throws CollabValidationException 任一环节失败
     */
    public static <T> T parse(String json, Class<T> type) {
        preCheckVersion(json, type);
        T dto = deserialize(json, type);
        applyTypeRules(dto);
        return dto;
    }

    /** 仅校验不取结果 */
    public static <T> void validate(String json, Class<T> type) {
        parse(json, type);
    }

    // ==================== 版本预检 ====================

    private static void preCheckVersion(String json, Class<?> type) {
        JSONObject object;
        try {
            object = JSON.parseObject(json);
        } catch (JSONException e) {
            throw invalid(type, "（根节点）", "JSON 语法错误: " + rootMessage(e), json, e);
        }
        if (object == null) {
            throw invalid(type, "（根节点）", "内容为空或不是 JSON 对象", json, null);
        }
        Integer version = object.getInteger("schemaVersion");
        if (version == null) {
            throw invalid(type, "schemaVersion",
                    "字段缺失，期望 " + EXPECTED_SCHEMA_VERSION, json, null);
        }
        if (version != EXPECTED_SCHEMA_VERSION) {
            throw invalid(type, "schemaVersion",
                    "版本不符: 期望 " + EXPECTED_SCHEMA_VERSION + "，实际 " + version, json, null);
        }
    }

    // ==================== 反序列化 ====================

    private static <T> T deserialize(String json, Class<T> type) {
        try {
            return JSON.parseObject(json, type);
        } catch (JSONException e) {
            throw invalid(type, "（结构）", "JSON 反序列化失败: " + rootMessage(e), json, e);
        }
    }

    // ==================== 类型级规则 ====================

    private static void applyTypeRules(Object dto) {
        switch (dto) {
            case TaskBacklog backlog -> checkTaskBacklog(backlog);
            case ReviewReport report -> checkReviewReport(report);
            case RoutePlan routePlan -> checkRoutePlan(routePlan);
            case IntakeResult intake -> checkIntakeStatus(intake);
            default -> {
                // 其余契约仅依赖 DTO 紧凑构造器的单字段校验
            }
        }
    }

    private static void checkTaskBacklog(TaskBacklog backlog) {
        Set<String> seen = new HashSet<>();
        List<TaskBacklog.TaskItem> tasks = backlog.tasks();
        for (int i = 0; i < tasks.size(); i++) {
            String taskId = tasks.get(i).taskId();
            if (taskId.isBlank()) {
                throw rule("TaskBacklog", "tasks[" + i + "].taskId", "为空或空白");
            }
            if (!seen.add(taskId)) {
                throw rule("TaskBacklog", "tasks[" + i + "].taskId", "重复: " + taskId);
            }
        }
    }

    private static void checkReviewReport(ReviewReport report) {
        ReviewReport.ReviewScores s = report.scores();
        int sum = s.completeness() + s.feasibility() + s.timeConflict()
                + s.costBudget() + s.poiReasonableness();
        if (Math.abs(report.total() - sum) > REVIEW_TOTAL_TOLERANCE) {
            throw rule("ReviewReport", "total",
                    "总分 " + report.total() + " 与 5 维之和 " + sum
                            + " 偏差超过 ±" + REVIEW_TOTAL_TOLERANCE);
        }
    }

    private static void checkRoutePlan(RoutePlan routePlan) {
        for (RoutePlan.RouteDay day : routePlan.days()) {
            int sum = day.nodes().stream()
                    .map(RoutePlan.RouteNode::commuteMinutesFromPrev)
                    .filter(Objects::nonNull)
                    .mapToInt(Integer::intValue)
                    .sum();
            if (day.commuteMinutesTotal() > sum + COMMUTE_TOTAL_TOLERANCE_MIN) {
                throw rule("RoutePlan", "days[" + day.day() + "].commuteMinutesTotal",
                        day.commuteMinutesTotal() + " 超过当日各段之和(" + sum
                                + ")+" + COMMUTE_TOTAL_TOLERANCE_MIN);
            }
        }
    }

    private static void checkIntakeStatus(IntakeResult intake) {
        // 非法枚举值在反序列化阶段已被 fastjson2 拒绝（JSONException），
        // status 非空由 DTO 紧凑构造器 requireNonNull 保证——此规则为显式防御锚点
        if (intake.status() == null) {
            throw rule("IntakeResult", "status", "缺失或非法（合法值: COLLECTING/DONE/DEGRADED）");
        }
    }

    // ==================== 异常构造 ====================

    /** 类型级规则失败：类型名 + 字段路径 + 原因（DTO 已反序列化成功，无需原文片段） */
    private static CollabValidationException rule(String type, String path, String reason) {
        return new CollabValidationException("[" + type + "] " + path + " " + reason);
    }

    /** 解析失败：类型名 + 位置 + 原因 + 原文前 200 字符 */
    private static CollabValidationException invalid(Class<?> type, String path, String reason,
                                                     String raw, Throwable cause) {
        String snippet;
        if (raw == null) {
            snippet = "null";
        } else if (raw.length() <= RAW_SNIPPET_LIMIT) {
            snippet = raw;
        } else {
            snippet = raw.substring(0, RAW_SNIPPET_LIMIT) + "…";
        }
        String message = "[" + type.getSimpleName() + "] " + path + " " + reason
                + "；原文前" + RAW_SNIPPET_LIMIT + "字符: " + snippet;
        return cause == null
                ? new CollabValidationException(message)
                : new CollabValidationException(message, cause);
    }

    /** 取异常链根因消息（fastjson2 包装的 JSONException，根因才是 DTO 构造器的具体原因） */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return String.valueOf(t.getMessage());
    }
}
