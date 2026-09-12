package com.travelscope.agent.tools;

import com.travelscope.service.TaskRegistry;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 任务容器工具（需求 2：任务写入/查询/回报的唯一代码层入口）
 * <p>
 * 注册进全局 Toolkit，主 Agent 与规划子 Agent（SHARED 工作区、共享 Toolkit）均可调用：
 * <ul>
 *   <li>{@code create_task_backlog}：主 Agent 专属——把拆分好的任务清单登记进任务容器
 *       （内存队列 + 用户/会话隔离的 MD 文件），是委派 planning-agent 的前置条件；
 *       PlanningGateMiddleware 会在 agent_spawn 前强制校验</li>
 *   <li>{@code get_task_progress}：主 Agent 查询剩余未完成任务</li>
 *   <li>{@code update_task_status}：子 Agent 执行过程中逐项回报状态</li>
 * </ul>
 * userId 取自 Toolkit 注入的 RuntimeContext（方法参数不带 @ToolParam 即为上下文注入），
 * sessionId 由本轮路由指令明确给出；TaskRegistry 按 userId+sessionId 双键隔离，
 * 错号/越权一律返回 ERROR。
 * </p>
 */
public class TaskTools {

    private static final Logger log = LoggerFactory.getLogger(TaskTools.class);

    private final TaskRegistry taskRegistry;

    public TaskTools(TaskRegistry taskRegistry) {
        this.taskRegistry = taskRegistry;
    }

    /**
     * 登记任务清单：主 Agent 把拆分结果写入任务容器（MD 文件 + 内存队列）
     *
     * @param ctx Toolkit 自动注入的运行时上下文（取 userId，勿加 @ToolParam 注解）
     */
    @Tool(description = "登记行程规划任务清单到任务容器（写入 task_backlog.md 并注册内存队列）。"
            + "委派 planning-agent 之前必须先调用本工具；tasksJson 为 JSON 数组，"
            + "每项形如 {\"taskId\":\"T1\",\"description\":\"查询目的地天气预报\",\"suggestedTool\":\"weather-query\",\"priority\":\"P0\"}")
    public String create_task_backlog(
            @ToolParam(name = "sessionId", description = "本轮会话 ID，使用路由指令中给出的值，如 conv-13")
            String sessionId,
            @ToolParam(name = "tasksJson", description = "任务清单 JSON 数组字符串，按优先级 P0/P1/P2 排列")
            String tasksJson,
            RuntimeContext ctx) {
        String userId = ctx.getUserId();
        log.info("create_task_backlog: 用户={}, 会话={}", userId, sessionId);
        return taskRegistry.createBacklog(userId, sessionId, tasksJson);
    }

    /**
     * 查询任务进度：主 Agent 查看还有多少任务未完成
     */
    @Tool(description = "查询当前会话任务清单的执行进度（共几项、已完成/进行中/待处理/失败的数量与未完成任务列表）")
    public String get_task_progress(
            @ToolParam(name = "sessionId", description = "本轮会话 ID，使用路由指令中给出的值")
            String sessionId,
            RuntimeContext ctx) {
        return taskRegistry.progress(ctx.getUserId(), sessionId);
    }

    /**
     * 更新任务状态：子 Agent 每完成/失败一项任务即回报
     */
    @Tool(description = "更新指定任务的执行状态。planning-agent 每完成一项任务必须调用本工具回报")
    public String update_task_status(
            @ToolParam(name = "sessionId", description = "本轮会话 ID，与任务清单登记时一致")
            String sessionId,
            @ToolParam(name = "taskId", description = "任务 ID，如 T1")
            String taskId,
            @ToolParam(name = "status", description = "新状态：IN_PROGRESS 开始执行 / DONE 成功 / FAILED 失败")
            String status,
            RuntimeContext ctx) {
        return taskRegistry.updateStatus(ctx.getUserId(), sessionId, taskId, status);
    }

    /**
     * 委派门禁的纠正提示（PlanningGateMiddleware 拦截 agent_spawn 后重定向到此工具），Agent 不要主动调用
     */
    @Tool(description = "系统内部工具：委派被拦截时的纠正提示，不要主动调用")
    public String planning_gate_hint(
            @ToolParam(name = "reason", description = "拦截原因")
            String reason) {
        return reason;
    }
}
