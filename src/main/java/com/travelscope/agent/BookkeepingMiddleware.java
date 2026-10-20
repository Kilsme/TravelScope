package com.travelscope.agent;

import com.travelscope.collab.TaskWorkspace;
import com.travelscope.service.TaskRegistry;
import com.travelscope.service.TaskResultCache;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 记账类调用去 LLM 化（延迟优化①，2026-10-20）：把 planner 的
 * {@code update_task_status}（任务状态回报）与 {@code register_task_result}
 * （任务结果缓存登记）从 LLM 工具调用降级为编排副作用——
 * planner 的每个数据工具 Start/End 事件流经本中间件时，按工具名确定性映射：
 * <ul>
 *   <li>任务状态机（清单为代码模板 T1~T5，见 TaskBacklogPlanner）：
 *       工具 Start → 对应任务 IN_PROGRESS；End 成功（且结果非 ERROR 文本）→ DONE；
 *       T3 景点特殊——retrieve_poi_candidates 只标 IN_PROGRESS，写
 *       poi_shortlist.json 成功才 DONE（任务语义=筛选产出 shortlist）</li>
 *   <li>结果缓存登记（TaskResultCache，TTL 由类型决定）：天气/酒店/车票/机票/
 *       POI 候选池/路线各自登记工具返回全文；assemble_itinerary 成功 →
 *       读 itinerary_draft.json 登记 itinerary（draft markdownBody）</li>
 * </ul>
 * 非 Spring Bean（@Component 会与 AgentConfig→TaskRegistry→… 形成循环依赖），
 * 由 buildPlannerAgent 构造传入，编排路径与 master 兜底路径全覆盖；
 * reviewer 是独立 Agent 不经本中间件（其回炉修订仍保留 register_task_result 工具自登记）。
 * 任何异常只记日志，绝不影响业务流。
 */
public class BookkeepingMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(BookkeepingMiddleware.class);

    /** 数据工具 → 记账映射（taskId 为代码模板清单固定 ID；taskType 为缓存类型） */
    record Mapping(String taskId, TaskResultCache.TaskType taskType) {
    }

    /** 查映射（mcp__ 前缀走前缀匹配；不匹配返回 null） */
    static Mapping mapTool(String toolName) {
        if (toolName == null) {
            return null;
        }
        switch (toolName) {
            case "getWeatherForecast", "getWeather" -> {
                return new Mapping("T2", TaskResultCache.TaskType.WEATHER);
            }
            case "searchHotels" -> {
                return new Mapping("T4", TaskResultCache.TaskType.HOTEL);
            }
            case "queryTrainTickets" -> {
                return new Mapping("T1", TaskResultCache.TaskType.TRAIN);
            }
            case "queryFlightTickets" -> {
                return new Mapping("T1", TaskResultCache.TaskType.FLIGHT);
            }
            case "retrieve_poi_candidates" -> {
                return new Mapping("T3", TaskResultCache.TaskType.POI);
            }
            case "plan_route" -> {
                return new Mapping("T5", TaskResultCache.TaskType.ROUTE);
            }
            default -> {
                if (toolName.startsWith("mcp__c12306")) {
                    return new Mapping("T1", TaskResultCache.TaskType.TRAIN);
                }
                if (toolName.startsWith("mcp__variflight")) {
                    return new Mapping("T1", TaskResultCache.TaskType.FLIGHT);
                }
                return null;
            }
        }
    }

    private final TaskRegistry taskRegistry;
    private final TaskResultCache taskResultCache;
    private final TaskWorkspace taskWorkspace;

    public BookkeepingMiddleware(TaskRegistry taskRegistry, TaskResultCache taskResultCache,
                                 TaskWorkspace taskWorkspace) {
        this.taskRegistry = taskRegistry;
        this.taskResultCache = taskResultCache;
        this.taskWorkspace = taskWorkspace;
    }

    @Override
    public int order() {
        // 在 ObservationMiddleware(-800) 之后即可；副作用只读事件，不改变流
        return -700;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        String userId = ctx.getUserId();
        String sessionId = ctx.getSessionId();
        if (userId == null || sessionId == null) {
            return next.apply(input);
        }
        Map<String, StringBuilder> output = new ConcurrentHashMap<>();
        return next.apply(input).doOnNext(event -> {
            try {
                if (event instanceof ToolResultStartEvent e) {
                    Mapping m = mapTool(e.getToolCallName());
                    if (m != null) {
                        mark(userId, sessionId, m.taskId(), TaskRegistry.STATUS_IN_PROGRESS);
                    }
                } else if (event instanceof ToolResultTextDeltaEvent e) {
                    output.computeIfAbsent(e.getToolCallId(), id -> new StringBuilder()).append(e.getDelta());
                } else if (event instanceof ToolResultEndEvent e) {
                    StringBuilder text = output.remove(e.getToolCallId());
                    String result = text == null ? "" : text.toString().strip();
                    boolean ok = e.getState() == ToolResultState.SUCCESS && !result.startsWith("ERROR");
                    if (!ok) {
                        return;
                    }
                    if ("write_collab_file".equals(e.getToolCallName())) {
                        String fileName = fileNameOf(input, e.getToolCallId());
                        if ("poi_shortlist.json".equals(fileName)) {
                            mark(userId, sessionId, "T3", TaskRegistry.STATUS_DONE);
                        }
                        return;
                    }
                    if ("assemble_itinerary".equals(e.getToolCallName())) {
                        registerItinerary(userId, sessionId);
                        return;
                    }
                    Mapping m = mapTool(e.getToolCallName());
                    if (m == null) {
                        return;
                    }
                    // T3 的 DONE 语义=shortlist 落盘（见类注释），检索成功只登记候选池缓存
                    if (!"T3".equals(m.taskId())) {
                        mark(userId, sessionId, m.taskId(), TaskRegistry.STATUS_DONE);
                    }
                    if (!result.isBlank()) {
                        taskResultCache.register(userId, sessionId, m.taskType(), m.taskId(), result);
                    }
                }
            } catch (Exception ex) {
                log.warn("记账副作用异常（不影响业务流）: 会话={}, 原因={}", sessionId, ex.getMessage());
            }
        });
    }

    /** 状态更新（容器不存在=非编排链路/评测上下文，静默跳过） */
    private void mark(String userId, String sessionId, String taskId, String status) {
        String r = taskRegistry.updateStatus(userId, sessionId, taskId, status);
        if (r != null && r.startsWith("ERROR")) {
            log.debug("记账状态跳过: 会话={}, 任务={}, 原因={}", sessionId, taskId, r);
        }
    }

    /** assemble_itinerary 成功后：读落盘草案登记 itinerary 缓存（二次规划/回炉复用） */
    private void registerItinerary(String userId, String sessionId) {
        try {
            taskWorkspace.readItineraryDraft(userId, sessionId).ifPresent(draft ->
                    taskResultCache.register(userId, sessionId,
                            TaskResultCache.TaskType.ITINERARY, null, draft.markdownBody()));
        } catch (Exception e) {
            log.warn("itinerary 缓存登记失败（不影响业务流）: 会话={}, 原因={}", sessionId, e.getMessage());
        }
    }

    /** 从本轮 toolCalls 里取 write_collab_file 的 fileName 入参 */
    private static String fileNameOf(ActingInput input, String toolCallId) {
        try {
            return input.toolCalls().stream()
                    .filter(t -> Objects.equals(t.getId(), toolCallId))
                    .findFirst()
                    .map(t -> Objects.toString(t.getInput().get("fileName"), ""))
                    .orElse("");
        } catch (Exception e) {
            return "";
        }
    }
}
