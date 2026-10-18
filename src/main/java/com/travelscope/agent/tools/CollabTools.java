package com.travelscope.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.travelscope.collab.CollabFileWriter;
import com.travelscope.collab.IntakeResult;
import com.travelscope.collab.ItineraryDraft;
import com.travelscope.collab.PoiShortlist;
import com.travelscope.collab.ReviewPassed;
import com.travelscope.collab.ReviewReport;
import com.travelscope.collab.RoutePlan;
import com.travelscope.collab.TaskWorkspace;
import com.travelscope.service.retrieval.PoiRetrievalService;
import com.travelscope.service.retrieval.dto.PoiCandidatePool;
import com.travelscope.service.route.RoutePlannerServiceImpl;
import com.travelscope.service.route.dto.PoiCandidate;
import com.travelscope.service.route.dto.RouteConstraints;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 协作契约工具三件套（S2.6）：planning-agent 触发代码服务与落盘契约文件的唯一入口。
 * <ul>
 *   <li>{@code retrieve_poi_candidates}：POI 候选池检索（PoiRetrievalService 批量编排，零 LLM）</li>
 *   <li>{@code plan_route}：分日路线求解（RoutePlannerService VRPTW 代码求解，零 LLM），
 *       输入读会话目录的 poi_shortlist.json，天数/目的地取 intake_result.json</li>
 *   <li>{@code write_collab_file}：契约文件落盘（CollabFileWriter：Schema 校验 + 原子写，
 *       文件名→DTO 强绑定），白名单四契约：poi_shortlist / itinerary_draft /
 *       review_report / review_passed（后两个为 S2.7 reviewer 切换预置）</li>
 * </ul>
 * userId 经 RuntimeContext 注入（不加 @ToolParam）；sessionId 由委派说明显式给出。
 * 全部错误以 ERROR 前缀文本返回（引导 agent 修正调用），不抛异常打断 ReAct 循环。
 */
@Service
public class CollabTools {

    private static final Logger log = LoggerFactory.getLogger(CollabTools.class);

    /** write_collab_file 白名单：契约文件名 → DTO 类型（与 CollabFileWriter 绑定一致） */
    private static final Map<String, Class<?>> SUPPORTED_FILES = Map.of(
            "poi_shortlist.json", PoiShortlist.class,
            "itinerary_draft.json", ItineraryDraft.class,
            "review_report.json", ReviewReport.class,
            "review_passed.json", ReviewPassed.class);

    private final PoiRetrievalService poiRetrievalService;
    private final RoutePlannerServiceImpl routePlannerService;
    private final TaskWorkspace taskWorkspace;
    private final CollabFileWriter collabFileWriter;

    public CollabTools(PoiRetrievalService poiRetrievalService,
                       RoutePlannerServiceImpl routePlannerService,
                       TaskWorkspace taskWorkspace,
                       CollabFileWriter collabFileWriter) {
        this.poiRetrievalService = poiRetrievalService;
        this.routePlannerService = routePlannerService;
        this.taskWorkspace = taskWorkspace;
        this.collabFileWriter = collabFileWriter;
    }

    /**
     * 检索 POI 候选池：知识库双路召回 + 硬字段补全，一次成型（零 LLM）。
     * 返回候选池 JSON，筛选排序是调用方（planning-agent）的职责。
     */
    @Tool(description = "检索目的地 POI 候选池（知识库 RAG+ES 双路召回 + 高德硬字段补全，批量一次成型）。"
            + "返回候选池 JSON：candidates 每条含 chunkId/content(片段原文)/source/score(融合分)/"
            + "vectorScore(余弦相似度)/hitVariants，stats 为池统计。拿到后由你做偏好筛选排序，"
            +  "结果经 write_collab_file 写 poi_shortlist.json")
    public String retrieve_poi_candidates(
            @ToolParam(name = "destination", description = "目的地城市，如「杭州」")
            String destination,
            @ToolParam(name = "days", description = "行程天数（候选池规模目标 ≥ 2×days）")
            int days,
            @ToolParam(name = "preferences", description = "用户偏好关键词，逗号分隔（如「博物馆,美食」），可空",
                    required = false)
            String preferences,
            RuntimeContext ctx) {
        List<String> prefs = preferences == null || preferences.isBlank()
                ? List.of()
                : Arrays.stream(preferences.split("[,，]"))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
        log.info("retrieve_poi_candidates: 用户={}, 目的地={}, days={}, 偏好={}",
                ctx.getUserId(), destination, days, prefs);
        PoiCandidatePool pool = poiRetrievalService.retrieve(destination, prefs, days);
        return JSON.toJSONString(pool);
    }

    /**
     * 分日路线求解并落盘 route_plan.json。输入全部来自会话目录契约文件：
     * poi_shortlist.json（POI 清单）+ intake_result.json（天数/目的地）。
     * 覆盖约束参数用于质检回炉后的调参重排。
     */
    @Tool(description = "分日路线求解（代码 VRPTW：最近邻构造 + 2-opt 优化 + 时段顺排，零 LLM），"
            + "求解结果落盘 route_plan.json 并返回摘要。前置：本会话已用 write_collab_file 写入 "
            + "poi_shortlist.json；天数与目的地自动取 intake_result.json。"
            + "maxDailyCommuteMin/preferTransit 为可选约束覆盖，供质检回炉调参重排用")
    public String plan_route(
            @ToolParam(name = "sessionId", description = "本轮会话 ID，使用委派说明中给出的值，如 conv-13")
            String sessionId,
            @ToolParam(name = "maxDailyCommuteMin", description = "单日通勤上限分钟数（默认 90）",
                    required = false)
            Integer maxDailyCommuteMin,
            @ToolParam(name = "preferTransit", description = "公交优先（默认 true；false=驾车优先）",
                    required = false)
            Boolean preferTransit,
            RuntimeContext ctx) {
        String userId = ctx.getUserId();
        // 天数与目的地取需求契约（唯一事实源，防止 agent 传参与需求漂移）
        Optional<IntakeResult> intake = taskWorkspace.readIntakeResult(userId, sessionId);
        if (intake.isEmpty()) {
            return "ERROR: 会话目录缺 intake_result.json（需求未收齐即开始规划？），无法确定天数与目的地";
        }
        String daysStr = intake.get().fields().days() == null ? null : intake.get().fields().days().value();
        String destination = intake.get().fields().destination() == null
                ? null : intake.get().fields().destination().value();
        int days;
        try {
            days = Integer.parseInt(daysStr);
        } catch (Exception e) {
            return "ERROR: intake_result.json 中天数缺失或非法: " + daysStr;
        }
        if (destination == null || destination.isBlank()) {
            return "ERROR: intake_result.json 中目的地缺失";
        }
        // POI 清单取筛选契约（planning-agent 第 2 步产出）
        Optional<PoiShortlist> shortlist = taskWorkspace.readPoiShortlist(userId, sessionId);
        if (shortlist.isEmpty()) {
            return "ERROR: 会话目录缺 poi_shortlist.json——先完成 POI 筛选并用 write_collab_file 写入";
        }
        List<PoiCandidate> pois = shortlist.get().pois().stream().map(CollabTools::toCandidate).toList();
        if (pois.isEmpty()) {
            return "ERROR: poi_shortlist.json 的 pois 为空——先完成 POI 筛选";
        }
        RouteConstraints constraints = RouteConstraints.ofDays(days);
        if (maxDailyCommuteMin != null) {
            constraints = new RouteConstraints(constraints.days(), constraints.dailyStart(),
                    constraints.dailyEnd(), constraints.lunchStart(), constraints.lunchEnd(),
                    maxDailyCommuteMin, constraints.transferBufferMin(), constraints.hotelName(),
                    constraints.hotelLng(), constraints.hotelLat(), constraints.preferTransit());
        }
        if (preferTransit != null) {
            constraints = constraints.withPreferTransit(preferTransit);
        }
        log.info("plan_route: 用户={}, 会话={}, POI数={}, days={}, 城市={}",
                userId, sessionId, pois.size(), days, destination);
        try {
            RoutePlan plan = routePlannerService.planAndWrite(userId, sessionId, pois,
                    constraints, destination);
            return JSON.toJSONString(plan);
        } catch (Exception e) {
            log.warn("plan_route 求解失败: 用户={}, 会话={}, 原因={}", userId, sessionId, e.getMessage());
            return "ERROR: 路线求解失败: " + e.getMessage();
        }
    }

    /**
     * 契约文件落盘：contentJson 解析为绑定 DTO（schemaVersion 缺省自动补 4），
     * 经 CollabFileWriter 全量 Schema 校验 + 原子写入。校验/解析失败返回 ERROR 不写盘。
     */
    @Tool(description = "写协作契约文件（Schema 校验 + 原子落盘，校验不过不写盘）。"
            + "支持的 fileName：poi_shortlist.json（POI 筛选清单）/ itinerary_draft.json（行程草案）/"
            + "review_report.json / review_passed.json（评审产物）。contentJson 为契约 JSON 字符串，"
            + "字段名与契约组件同名；schemaVersion 可缺省（自动补 4）")
    public String write_collab_file(
            @ToolParam(name = "sessionId", description = "本轮会话 ID，使用委派说明中给出的值")
            String sessionId,
            @ToolParam(name = "fileName", description = "契约文件名（poi_shortlist.json / itinerary_draft.json 等）")
            String fileName,
            @ToolParam(name = "contentJson", description = "契约内容 JSON 字符串")
            String contentJson,
            RuntimeContext ctx) {
        String userId = ctx.getUserId();
        Class<?> type = SUPPORTED_FILES.get(fileName);
        if (type == null) {
            return "ERROR: 不支持的契约文件名「" + fileName + "」，支持: "
                    + String.join(" / ", SUPPORTED_FILES.keySet());
        }
        Object dto;
        try {
            // schemaVersion 缺省补 4：LLM 调用方不必感知契约版本号
            JSONObject json = JSON.parseObject(contentJson);
            json.putIfAbsent("schemaVersion", 4);
            dto = json.toJavaObject(type);
        } catch (Exception e) {
            return "ERROR: contentJson 解析为 " + type.getSimpleName() + " 失败: " + e.getMessage()
                    + "（检查字段名与契约结构）";
        }
        try {
            collabFileWriter.write(taskWorkspace.getTaskDir(userId, sessionId), fileName, dto);
        } catch (Exception e) {
            log.warn("write_collab_file 校验/落盘失败: 用户={}, 会话={}, file={}, 原因={}",
                    userId, sessionId, fileName, e.getMessage());
            return "ERROR: " + fileName + " 校验未通过: " + e.getMessage();
        }
        log.info("write_collab_file 已落盘: 用户={}, 会话={}, file={}", userId, sessionId, fileName);
        return "已写入 tasks/" + sessionId + "/" + fileName;
    }

    /** PoiShortlist.PoiItem → 求解输入 PoiCandidate（openHours「HH:mm-HH:mm」拆解为开放窗；缺失=全天开放） */
    private static PoiCandidate toCandidate(PoiShortlist.PoiItem item) {
        String openStart = null;
        String openClose = null;
        if (item.openHours() != null && item.openHours().contains("-")) {
            String[] parts = item.openHours().split("-", 2);
            String start = parts[0].trim();
            String end = parts[1].trim();
            if (start.matches("\\d{1,2}:\\d{2}") && end.matches("\\d{1,2}:\\d{2}")) {
                openStart = start;
                openClose = end;
            }
        }
        return new PoiCandidate(item.name(), item.lng(), item.lat(), openStart, openClose,
                item.durationMin(), item.rating(), item.source());
    }
}
