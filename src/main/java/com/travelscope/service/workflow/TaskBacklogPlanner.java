package com.travelscope.service.workflow;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.travelscope.collab.IntakeResult;
import com.travelscope.collab.TaskBacklog;
import com.travelscope.collab.TaskWorkspace;
import com.travelscope.dto.PlanningTask;
import com.travelscope.service.TaskRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 代码模板任务拆分器（S2.4）：需求收齐后由代码确定性生成 task_backlog.json，
 * 替代 master 的 LLM 拆任务（零 LLM，输出可复现）。
 * <p>
 * 固定生成 T1~T5 五条任务（ID 与顺序固定，优先级语义同 v3：P0 必做 / P1 重要）：
 * <ol>
 *   <li><b>T1 交通</b>：fromCity≠destination → 城际大交通（train-ticket-query；
 *       preference/special 原文含机票关键词时升级 flight-ticket-query）；
 *       fromCity 缺失或与目的地同城（含 DEGRADED 默认同城）→ 降级为市内交通接驳任务</li>
 *   <li><b>T2 天气</b>（weather-query，P0）、<b>T3 景点</b>（poi-retrieval，P0）、
 *       <b>T4 酒店</b>（hotel-search，P1）、<b>T5 路线</b>（route-planner，P1）；
 *       工具名与 v4 代码服务词汇对齐（poi-retrieval ↔ PoiRetrievalService，
 *       route-planner ↔ RoutePlannerService）</li>
 * </ol>
 * 描述由 intake 字段模板填充（目的地/天数/日期/人数/偏好原文嵌入），并内置四维关键词
 * （交通/住宿/景点/天气），保证 {@link TaskRegistry} 的 DIMENSION_RULES 双路匹配必过。
 * </p>
 * <p>
 * 自检与落盘复用 S0.6 链路：直接调 {@link TaskRegistry#createBacklog}（其内部即
 * DIMENSION_RULES 校验 + TaskWorkspace 类型化落盘 + 内存容器注册），返回 ERROR 即
 * 模板 bug → IllegalStateException 快速失败；成功后经
 * {@link TaskWorkspace#readTaskBacklog} 读回落盘契约返回。
 * </p>
 */
@Service
public class TaskBacklogPlanner {

    private static final Logger log = LoggerFactory.getLogger(TaskBacklogPlanner.class);

    /** 工具/技能名（poi-retrieval、route-planner 为 v4 代码服务名，与计划文档词汇一致） */
    static final String TOOL_TRAIN = "train-ticket-query";
    static final String TOOL_FLIGHT = "flight-ticket-query";
    static final String TOOL_WEATHER = "weather-query";
    static final String TOOL_POI = "poi-retrieval";
    static final String TOOL_HOTEL = "hotel-search";
    static final String TOOL_ROUTE = "route-planner";

    /** T1 机票升级关键词：preference/special 原文命中即改建议 flight-ticket-query */
    private static final List<String> FLIGHT_KEYWORDS = List.of("飞机", "航班", "机票");

    /** 优先级（语义同 v3：P0 必做 / P1 重要） */
    private static final String P0 = "P0";
    private static final String P1 = "P1";

    private final TaskRegistry taskRegistry;
    private final TaskWorkspace taskWorkspace;

    public TaskBacklogPlanner(TaskRegistry taskRegistry, TaskWorkspace taskWorkspace) {
        this.taskRegistry = taskRegistry;
        this.taskWorkspace = taskWorkspace;
    }

    /**
     * 按 intake 确定性生成任务清单并登记落盘（S2.5 编排链 step2 调用入口）。
     *
     * @param userId 落盘路径属主（TaskRegistry/TaskWorkspace 的用户+会话双级隔离要求）
     * @param intake 已收齐的需求契约（DONE 或 DEGRADED）
     * @return 落盘后的 task_backlog.json 契约（读回自 TaskWorkspace，与磁盘一致）
     * @throws IllegalArgumentException intake 未收齐（COLLECTING）
     * @throws IllegalStateException    必填字段缺失（契约破裂）或代码模板清单未过四维校验（模板 bug）
     */
    public TaskBacklog plan(String userId, IntakeResult intake) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(intake, "intake");
        if (intake.status() == IntakeResult.Status.COLLECTING) {
            throw new IllegalArgumentException("intake 未收齐（COLLECTING），不能生成任务清单: " + intake.sessionId());
        }
        List<PlanningTask> tasks = buildTasks(intake.fields());
        String result = taskRegistry.createBacklog(userId, intake.sessionId(), toTasksJson(tasks));
        if (result.startsWith("ERROR")) {
            // 代码生成的清单必过四维校验；不过 = 模板 bug，快速失败
            throw new IllegalStateException("代码模板清单未通过登记校验（模板 bug）: " + result);
        }
        TaskBacklog backlog = taskWorkspace.readTaskBacklog(userId, intake.sessionId())
                .orElseThrow(() -> new IllegalStateException(
                        "登记后读回 task_backlog.json 失败: " + intake.sessionId()));
        log.info("任务清单已由代码模板生成: 会话={}, 任务数={}", intake.sessionId(), backlog.tasks().size());
        return backlog;
    }

    // ==================== 内部实现 ====================

    /** 固定生成 T1~T5 五条任务（顺序即清单顺序） */
    private List<PlanningTask> buildTasks(IntakeResult.RequirementFields f) {
        String dest = required(f.destination(), "destination");
        String days = required(f.days(), "days");
        String date = required(f.startDate(), "startDate");
        String from = val(f.fromCity());
        String people = val(f.people());
        String pref = val(f.preference());
        String budget = budgetSummary(f);
        String special = val(f.special());

        List<PlanningTask> tasks = new ArrayList<>(5);
        tasks.add(transportTask(dest, days, date, from, people, pref, special));
        tasks.add(task("T2",
                "查询" + dest + " " + date + "起" + days + "天的天气预报与穿衣建议",
                TOOL_WEATHER, P0));
        tasks.add(task("T3",
                "检索" + dest + "景点 POI 候选池（" + days + "天行程）并按偏好筛选产出 poi_shortlist"
                        + (pref == null ? "" : "；偏好原文：「" + pref + "」"),
                TOOL_POI, P0));
        tasks.add(task("T4",
                "搜索" + dest + "酒店住宿：" + date + "起" + days + "天"
                        + (people == null ? "" : "，共 " + people + " 人")
                        + (budget == null ? "" : "；预算：" + budget)
                        + (special == null ? "" : "；特殊要求：「" + special + "」")
                        + "，回填酒店名与地址",
                TOOL_HOTEL, P1));
        tasks.add(task("T5",
                "规划" + dest + " " + days + "天逐日路线：景点串线与通勤时段编排，产出行程骨架",
                TOOL_ROUTE, P1));
        return tasks;
    }

    /**
     * T1 交通任务：fromCity≠destination 时为城际大交通（P0）；fromCity 缺失或与目的地
     * 同城时降级为市内交通接驳任务（suggestedTool 缺省）。描述内置「交通/火车/机票/接驳」
     * 关键词，保证四维校验的交通维经 description 双路匹配必过。
     */
    private PlanningTask transportTask(String dest, String days, String date, String from,
                                       String people, String pref, String special) {
        if (from == null || from.equals(dest)) {
            return task("T1",
                    "规划" + dest + "市内交通接驳：" + days + "天行程的景点/酒店间通勤方案（地铁/公交/打车）",
                    null, P0);
        }
        boolean flight = prefersFlight(pref, special);
        String peopleClause = people == null ? "" : "，共 " + people + " 人";
        return task("T1",
                "查询" + from + "→" + dest + "的城际大交通：" + date + "出发" + peopleClause
                        + (flight ? "，预订机票并回填航班与票价" : "，预订高铁/火车票并回填车次与票价"),
                flight ? TOOL_FLIGHT : TOOL_TRAIN, P0);
    }

    /** preference/special 原文含机票关键词 → 建议 flight-ticket-query（确定性规则，零 LLM） */
    private static boolean prefersFlight(String pref, String special) {
        String text = (pref == null ? "" : pref) + (special == null ? "" : special);
        return FLIGHT_KEYWORDS.stream().anyMatch(text::contains);
    }

    private static PlanningTask task(String id, String description, String tool, String priority) {
        PlanningTask t = new PlanningTask();
        t.taskId = id;
        t.description = description;
        t.suggestedTool = tool;
        t.priority = priority;
        return t;
    }

    private static String budgetSummary(IntakeResult.RequirementFields fields) {
        var structured = fields.budgetConstraint();
        if (structured == null) {
            structured = new BudgetConstraintParser().parse(val(fields.budget())).constraint();
        }
        if (structured == null) return val(fields.budget());
        StringBuilder summary = new StringBuilder(structured.rawText());
        if (structured.scope() != null) summary.append(" [").append(structured.scope());
        if (structured.min() != null) summary.append(" min=").append(structured.min().toPlainString());
        if (structured.max() != null) summary.append(" max=").append(structured.max().toPlainString());
        if (structured.scope() != null) summary.append(']');
        return summary.toString();
    }

    /** 字段值（未收集 → null） */
    private static String val(IntakeResult.FieldValue fv) {
        return fv == null ? null : fv.value();
    }

    /** 必填字段值：缺失 = intake 契约破裂（DONE/DEGRADED 必填必有值），快速失败 */
    private static String required(IntakeResult.FieldValue fv, String name) {
        String v = val(fv);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("intake 必填字段缺失（契约破裂）: " + name);
        }
        return v;
    }

    /** 任务清单 → tasksJson（四键与 TaskRegistry.parseTasks 对齐；suggestedTool 为 null 时省略该键） */
    private static String toTasksJson(List<PlanningTask> tasks) {
        JSONArray arr = new JSONArray();
        for (PlanningTask t : tasks) {
            JSONObject jo = new JSONObject();
            jo.put("taskId", t.taskId);
            jo.put("description", t.description);
            if (t.suggestedTool != null) {
                jo.put("suggestedTool", t.suggestedTool);
            }
            jo.put("priority", t.priority);
            arr.add(jo);
        }
        return arr.toJSONString();
    }
}
