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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    private final com.travelscope.service.ItineraryAssembler itineraryAssembler;
    private final com.travelscope.service.TaskResultCache taskResultCache;
    private final io.agentscope.core.model.Model chatModel;
    private final AttractionTool attractionTool;

    public CollabTools(PoiRetrievalService poiRetrievalService,
                       RoutePlannerServiceImpl routePlannerService,
                       TaskWorkspace taskWorkspace,
                       CollabFileWriter collabFileWriter,
                       com.travelscope.service.ItineraryAssembler itineraryAssembler,
                       com.travelscope.service.TaskResultCache taskResultCache,
                       @org.springframework.beans.factory.annotation.Qualifier("gatewayChatModel")
                       io.agentscope.core.model.Model chatModel,
                       AttractionTool attractionTool) {
        this.poiRetrievalService = poiRetrievalService;
        this.routePlannerService = routePlannerService;
        this.taskWorkspace = taskWorkspace;
        this.collabFileWriter = collabFileWriter;
        this.itineraryAssembler = itineraryAssembler;
        this.taskResultCache = taskResultCache;
        this.chatModel = chatModel;
        this.attractionTool = attractionTool;
    }

    /**
     * 检索 POI 候选池：知识库双路召回 + 硬字段补全，一次成型（零 LLM）。
     * 返回候选池 JSON，筛选排序是调用方（planning-agent）的职责。
     */
    @Tool(description = "检索目的地 POI 候选池（知识库 RAG+ES 双路召回 + 高德硬字段补全，批量一次成型）。"
            + "返回候选池瘦身 JSON：candidates 每条含 content(片段摘要≤160字)/source/score(融合分)，"
            + "stats 为池统计（全量片段由代码侧缓存持有，select_poi_shortlist 会自动取用）。"
            + "拿到后由你做偏好筛选排序，结果经 select_poi_shortlist 传名单定案落盘")
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
        PoiCandidatePool pool = com.travelscope.assessment.FixtureServices.enabled(ctx)
                ? com.travelscope.assessment.FixtureServices.retrieve(poiRetrievalService, destination, prefs, days, ctx)
                : poiRetrievalService.retrieve(destination, prefs, days);
        // P2 上下文瘦身（v6）：模型视角返回瘦身视图（裁 chunkId/vectorScore/hitVariants + content
        // 截断）；全量 JSON 由工具自登记进 POI 缓存供代码消费（select 的 source 继承、
        // 缓存命中轮内联）——just-in-time 按需加载，BookkeepingMiddleware 对本工具跳过登记防覆盖
        try {
            taskResultCache.register(ctx.getUserId(), ctx.getSessionId(),
                    com.travelscope.service.TaskResultCache.TaskType.POI, "T3", JSON.toJSONString(pool));
        } catch (Exception e) {
            log.debug("候选池全量缓存登记失败（不影响返回）: {}", e.getMessage());
        }
        return pool.toSlimView(160);
    }

    /**
     * POI 筛选名单制（卡③：LLM 只传选中名单，代码校验候选池+补硬字段+落盘，零手写 JSON）。
     * 候选池来源=首轮 retrieve_poi_candidates 的返回全文（记账中间件登记进 POI 缓存）；
     * 名单校验=候选 content 文本 contains 命中（池外名直接拒绝，防幻觉加锁）；
     * lng/lat/rating/openHours 候选池无结构化字段（在 content 文本内）→ 置空（契约允许）。
     */
    @Tool(description = "POI 筛选定案（你只给选中名单，代码校验+补字段+落盘 poi_shortlist.json）。"
            + "names：选中的 POI 名（逗号/顿号分隔；候选池内优先，领域常识名也可——代码会逐一调实时 POI 库校验并补坐标/开放时段，查无此地会被整体拒绝）；"
            + "durationMins：与 names 同顺序的建议游玩分钟数（逗号分隔，可空，默认 120）；"
            + "reasons：与 names 同顺序的一句话入选理由（分号分隔，可空）。"
            + "数量目标 ≥ 2×days、总数 ≤ 4×days（每日 2~4 个配额，多选会被代码截断）。严禁手写 shortlist JSON")
    public String select_poi_shortlist(
            @ToolParam(name = "sessionId", description = "本轮会话 ID，使用委派说明中给出的值")
            String sessionId,
            @ToolParam(name = "names", description = "选中 POI 名，逗号/顿号分隔（必须来自候选池）")
            String names,
            @ToolParam(name = "durationMins", description = "与 names 同顺序的游玩分钟数，逗号分隔，可空",
                    required = false)
            String durationMins,
            @ToolParam(name = "reasons", description = "与 names 同顺序的一句话理由，分号分隔，可空",
                    required = false)
            String reasons,
            RuntimeContext ctx) {
        String userId = ctx.getUserId();
        try {
            Optional<IntakeResult> intake = taskWorkspace.readIntakeResult(userId, sessionId);
            if (intake.isEmpty()) {
                return "ERROR: 会话目录缺 intake_result.json";
            }
            String destination = intake.get().fields().destination() == null
                    ? null : intake.get().fields().destination().value();
            if (destination == null || destination.isBlank()) {
                return "ERROR: intake_result.json 中目的地缺失";
            }
            String daysStr = intake.get().fields().days() == null ? null : intake.get().fields().days().value();

            // 候选池（首轮 retrieve 工具返回的 JSON 全文，由记账中间件登记进 POI 缓存）
            String poolJson = taskResultCache.get(userId, sessionId,
                    com.travelscope.service.TaskResultCache.TaskType.POI);
            // 候选池只用于 source 标记继承：缺失/解析失败不阻断（硬门在 searchPois 实证）
            PoiCandidatePool pool = new PoiCandidatePool(List.of(),
                    new PoiCandidatePool.PoolStats(0, new java.util.LinkedHashMap<>(), 0, 0));
            if (poolJson != null && !poolJson.isBlank()) {
                try {
                    PoiCandidatePool parsed = JSON.parseObject(poolJson, PoiCandidatePool.class);
                    if (parsed != null && parsed.candidates() != null) {
                        pool = parsed;
                    }
                } catch (Exception e) {
                    log.debug("候选池缓存解析失败（仅丢 source 继承，不阻断）: {}", e.getMessage());
                }
            }

            List<String> selected = splitNames(names);
            if (selected.isEmpty()) {
                return "ERROR: names 为空——数量目标 ≥ 2×days 个 POI 名";
            }
            // L1d（v6）shortlist 4×days 硬截断：压矩阵规模（节点数平方级影响矩阵对数）
            // 与 unplaced 溢出触发面；截前 4×days（保序，durations/reasons 索引对齐不受影响），
            // 截断项在返回文本与日志中明示
            int days = parseDays(daysStr);
            List<String> truncated = List.of();
            if (days > 0 && selected.size() > 4 * days) {
                truncated = List.copyOf(selected.subList(4 * days, selected.size()));
                selected = new ArrayList<>(selected.subList(0, 4 * days));
                log.info("select_poi_shortlist 4×days 截断: 用户={}, 会话={}, {} → {} 个（截断 {} 项 {}）",
                        userId, sessionId, selected.size() + truncated.size(), selected.size(),
                        truncated.size(), truncated);
            }
            List<Integer> durations = parseIntList(durationMins);
            List<String> reasonList = splitReasons(reasons);

            // 定案规则（卡③+conv-56/57 实证修正）：候选池只是挑选建议（知识库种子偏杭州，
            // 池薄是常态），防幻觉硬锁改为「实时 POI 库实证存在」——每个名字调 searchPois
            // （高德/飞猪）验证并补硬字段（lng/lat/openHours/rating，路线求解要坐标）；
            // 池内命中 → source 沿用候选池来源标记；查无此地 → 真幻觉，拒绝。
            // L1a（v6 轮次合并）：实证解析并行化——resolvePoiItem 内部 = flyai CLI 子进程
            // + 高德 HTTP（IO 密集按约定走虚拟线程），按提交序 join 保序；
            // 失败拒绝语义不变（任一实证失败该名整体拒绝、零落盘）
            List<com.travelscope.collab.PoiShortlist.PoiItem> resolved = resolveNamesParallel(destination, selected);
            List<String> rejected = new ArrayList<>();
            List<com.travelscope.collab.PoiShortlist.PoiItem> items = new ArrayList<>();
            for (int i = 0; i < selected.size(); i++) {
                String name = selected.get(i);
                var inPool = pool.candidates().stream()
                        .filter(c -> c.content() != null && c.content().contains(name))
                        .findFirst();
                com.travelscope.collab.PoiShortlist.PoiItem r = resolved.get(i);
                if (r == null) {
                    rejected.add(name);
                    continue;
                }
                Integer dur = i < durations.size() ? durations.get(i) : null;
                String reason = i < reasonList.size() ? reasonList.get(i) : null;
                items.add(new com.travelscope.collab.PoiShortlist.PoiItem(
                        r.name(), r.lng(), r.lat(), r.rating(),
                        r.openHours(), dur == null ? 120 : dur,
                        inPool.map(c -> c.source()).orElse(r.source()),
                        reason == null ? r.reason() : reason));
            }
            // 防幻觉锁：池外名单整体拒绝，不部分落盘
            if (!rejected.isEmpty()) {
                return "ERROR: 以下 POI 在实时 POI 库中查无此地（未落盘任何内容）：" + rejected
                        + "——请核对名字（用候选池/常识中的正式名称）后重调";
            }

            String warn = days > 0 && items.size() < 2 * days
                    ? "（提示：选定 " + items.size() + " 个 < 2×days=" + (2 * days) + " 目标，确认是否漏选）" : "";
            String truncNote = truncated.isEmpty() ? ""
                    : "（已按 4×days=" + (4 * days) + " 上限截断以下 " + truncated.size()
                            + " 项：" + truncated + "）";

            taskWorkspace.writePoiShortlist(userId, sessionId, new com.travelscope.collab.PoiShortlist(
                    destination, "候选池 " + pool.candidates().size() + " 个，名单制筛选 " + items.size() + " 个",
                    items));
            log.info("select_poi_shortlist: 用户={}, 会话={}, 落盘 {} 个（候选池 {} 个）",
                    userId, sessionId, items.size(), pool.candidates().size());
            return "POI 筛选完成：已落盘 poi_shortlist.json 共 " + items.size() + " 个（候选池 "
                    + pool.candidates().size() + " 个）" + warn + truncNote + "。下一步调 plan_route。";
        } catch (Exception e) {
            log.warn("select_poi_shortlist 失败: 用户={}, 会话={}, 原因={}", userId, sessionId, e.getMessage());
            return "ERROR: POI 筛选落盘失败: " + e.getMessage();
        }
    }

    /**
     * L1c 收尾轮消除（v6·保守方案）：自动链成功返回文本末尾附终止指令与成品一行——
     * 模型零决策照抄即结束，省一整轮 LLM 调用（实测 ~4.4s）。
     * 标记消费方均 contains(AUTO_ASSEMBLED_MARKER) 判定，尾部追加零影响。
     * 抽为包私有静态：成功路径的文本契约（终止指令 + N 天 N 景）单测直接锚定。
     */
    static String autoChainSuccessText(String routeJson, String assembled, RoutePlan plan) {
        int poiTotal = plan.days().stream().mapToInt(d -> d.pois().size()).sum();
        return routeJson + "\n\n" + AUTO_ASSEMBLED_MARKER + "：" + assembled
                + "\n\n【系统终止指令】路线与草案均已落盘，流程已完成——请原样回复以下一行"
                + "（不得增删任何字符，无需再调任何工具）：行程草案已生成："
                + plan.days().size() + " 天 " + poiTotal + " 景";
    }

    /** L1a 实证解析并发上限（高德 /place/text 个人 key ~3/s，CommuteMatrixBuilder 同款限流先例） */
    private static final int RESOLVE_MAX_PARALLEL = 3;

    /**
     * L1a 名单实证并行解析（IO 密集按项目约定走虚拟线程）：每名单独 supplyAsync，
     * 按提交序 join 保序返回（与 selected 同序；null = 实证失败，由调用方整体拒绝）。
     */
    private List<com.travelscope.collab.PoiShortlist.PoiItem> resolveNamesParallel(
            String destination, List<String> selected) {
        java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(RESOLVE_MAX_PARALLEL);
        try (java.util.concurrent.ExecutorService executor =
                     java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.CompletableFuture<com.travelscope.collab.PoiShortlist.PoiItem>> futures =
                    selected.stream().map(name -> java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> resolveWithRetry(destination, name, permits), executor)).toList();
            return futures.stream().map(java.util.concurrent.CompletableFuture::join).toList();
        }
    }

    /**
     * 单名实证：Semaphore 限并发（防并行放大高德 QPS 抖动成整批拒绝）；
     * null 结果（多为限流/网络抖动）等待 600ms 整单重试一次——对齐 CommuteMatrixBuilder 修复先例。
     */
    private com.travelscope.collab.PoiShortlist.PoiItem resolveWithRetry(
            String destination, String name, java.util.concurrent.Semaphore permits) {
        try {
            permits.acquire();
            try {
                com.travelscope.collab.PoiShortlist.PoiItem r = resolvePoiItem(destination, name);
                if (r != null) {
                    return r;
                }
            } finally {
                permits.release();
            }
            Thread.sleep(600);
            return resolvePoiItem(destination, name);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return resolvePoiItem(destination, name);
        }
    }

    /** 逗号/顿号分隔的整数列表（非法项占位置 null，保持与 names 的同序对齐） */
    private static List<Integer> parseIntList(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (String t : text.split("[,，、]")) {
            try {
                out.add(Integer.parseInt(t.trim()));
            } catch (Exception e) {
                out.add(null);
            }
        }
        return out;
    }

    /** 分号分隔的理由列表（理由内可含逗号；分号在中文理由里罕见） */
    private static List<String> splitReasons(String reasons) {
        if (reasons == null || reasons.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(reasons.split("[;；]"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** 天数解析（非法/缺失按 1 天兜底——只用于召回池规模，不阻断） */
    private static int parseDays(String daysStr) {
        try {
            return Integer.parseInt(daysStr.trim());
        } catch (Exception e) {
            return 1;
        }
    }

    /** plan_route 无 unplaced 时自动链进组装的返回标记（中间件凭它补 ITINERARY 登记与草案产物捕获） */
    public static final String AUTO_ASSEMBLED_MARKER = "已自动完成组装落盘 itinerary_draft.json";

    /**
     * 分日路线求解并落盘 route_plan.json。输入全部来自会话目录契约文件：
     * poi_shortlist.json（POI 清单）+ intake_result.json（天数/目的地）。
     * 覆盖约束参数用于质检回炉后的调参重排。
     * 无 unplaced 时自动链进组装（LLM 免评估轮）：落盘 itinerary_draft.json 并返回合并摘要。
     */
    @Tool(description = "分日路线求解（代码 VRPTW：最近邻构造 + 2-opt 优化 + 时段顺排，零 LLM），"
            + "求解结果落盘 route_plan.json。前置：本会话已用 select_poi_shortlist 落盘 "
            + "poi_shortlist.json；天数与目的地自动取 intake_result.json。"
            + "无 unplaced 时会自动完成 itinerary_draft.json 组装落盘（返回合并摘要），"
            + "有 unplaced 时返回未排入清单由你取舍。maxDailyCommuteMin/preferTransit 为可选约束覆盖")
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
        return planRouteInternal(sessionId, maxDailyCommuteMin, preferTransit, ctx, true);
    }

    /** plan_route 主体；autoChain=false 供 revise_poi_selection 内部调用（重组装由它自己收尾，防双重组装） */
    private String planRouteInternal(
            String sessionId, Integer maxDailyCommuteMin, Boolean preferTransit,
            RuntimeContext ctx, boolean autoChain) {
        RoutePrep prep = prepareRoute(sessionId, maxDailyCommuteMin, preferTransit, ctx);
        if (prep.failed()) {
            return prep.error();
        }
        log.info("plan_route: 用户={}, 会话={}, POI数={}, days={}, 城市={}",
                prep.userId(), sessionId, prep.pois().size(), prep.days(), prep.destination());
        try {
            if (prep.fixture() && Boolean.TRUE.equals(ctx.get("assessment.toolFailure"))) {
                return "ERROR: FIXTURE injected external tool failure";
            }
            RoutePlannerServiceImpl planner = prep.fixture()
                    ? com.travelscope.assessment.FixtureServices.planner(taskWorkspace) : routePlannerService;
            RoutePlan plan = planner.planAndWrite(prep.userId(), sessionId, prep.pois(),
                    prep.constraints(), prep.destination());
            String routeJson = JSON.toJSONString(plan);
            // 无 unplaced → 代码自动链进组装（LLM 免评估轮）；组装失败降级为提示手动调
            boolean noUnplaced = plan.solverMeta() == null
                    || plan.solverMeta().unplaced() == null || plan.solverMeta().unplaced().isEmpty();
            if (autoChain && noUnplaced) {
                String assembled = doAssembleItinerary(sessionId, ctx);
                if (!assembled.startsWith("ERROR")) {
                    log.info("plan_route 无溢出，自动链进组装: 用户={}, 会话={}", prep.userId(), sessionId);
                    return autoChainSuccessText(routeJson, assembled, plan);
                }
                log.warn("plan_route 自动链组装失败（降级为提示手动组装）: 用户={}, 会话={}, 原因={}",
                        prep.userId(), sessionId, assembled);
                // 内嵌失败原因时剥掉 ERROR 前缀（本返回是成功级结果，防误判）
                String detail = assembled.startsWith("ERROR: ") ? assembled.substring(7) : assembled;
                return routeJson + "\n\n（自动组装未完成：" + detail + "——请手动调 assemble_itinerary）";
            }
            return routeJson;
        } catch (Exception e) {
            log.warn("plan_route 求解失败: 用户={}, 会话={}, 原因={}", prep.userId(), sessionId, e.getMessage());
            return "ERROR: 路线求解失败: " + e.getMessage();
        }
    }

    /** plan_route 输入准备结果：error 非空即失败（ERROR 文本直返调用方） */
    private record RoutePrep(String error, String userId, int days, String destination,
                             List<PoiCandidate> pois, RouteConstraints constraints, boolean fixture) {
        boolean failed() {
            return error != null;
        }
    }

    /** 输入准备（与 planRouteInternal 原逻辑一致）：intake 天数/目的地 + shortlist POI + 约束 + fixture 判定 */
    private RoutePrep prepareRoute(String sessionId, Integer maxDailyCommuteMin,
                                   Boolean preferTransit, RuntimeContext ctx) {
        String userId = ctx.getUserId();
        // 天数与目的地取需求契约（唯一事实源，防止 agent 传参与需求漂移）
        Optional<IntakeResult> intake = taskWorkspace.readIntakeResult(userId, sessionId);
        if (intake.isEmpty()) {
            return new RoutePrep("ERROR: 会话目录缺 intake_result.json（需求未收齐即开始规划？），无法确定天数与目的地",
                    userId, 0, null, null, null, false);
        }
        String daysStr = intake.get().fields().days() == null ? null : intake.get().fields().days().value();
        String destination = intake.get().fields().destination() == null
                ? null : intake.get().fields().destination().value();
        int days;
        try {
            days = Integer.parseInt(daysStr);
        } catch (Exception e) {
            return new RoutePrep("ERROR: intake_result.json 中天数缺失或非法: " + daysStr,
                    userId, 0, null, null, null, false);
        }
        if (destination == null || destination.isBlank()) {
            return new RoutePrep("ERROR: intake_result.json 中目的地缺失",
                    userId, days, null, null, null, false);
        }
        // POI 清单取筛选契约（planning-agent 第 2 步产出）
        Optional<PoiShortlist> shortlist = taskWorkspace.readPoiShortlist(userId, sessionId);
        if (shortlist.isEmpty()) {
            return new RoutePrep("ERROR: 会话目录缺 poi_shortlist.json——先完成 POI 筛选并用 write_collab_file 写入",
                    userId, days, destination, null, null, false);
        }
        List<PoiCandidate> pois = shortlist.get().pois().stream().map(CollabTools::toCandidate).toList();
        if (pois.isEmpty()) {
            return new RoutePrep("ERROR: poi_shortlist.json 的 pois 为空——先完成 POI 筛选",
                    userId, days, destination, null, null, false);
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
        boolean fixture = com.travelscope.assessment.FixtureServices.enabled(ctx);
        return new RoutePrep(null, userId, days, destination, pois, constraints, fixture);
    }

    /**
     * R2 增量重排（revise_poi_selection 专用）：全量求解 + 未变天指纹复用
     * （矩阵经对级缓存增量补查）。错误时 outcome 为 null。
     */
    private record IncrementalRouteResult(String error,
                                          RoutePlannerServiceImpl.IncrementalOutcome outcome) {
    }

    private IncrementalRouteResult planRouteIncremental(String sessionId, RuntimeContext ctx,
                                                        RoutePlan previous) {
        RoutePrep prep = prepareRoute(sessionId, null, null, ctx);
        if (prep.failed()) {
            return new IncrementalRouteResult(prep.error(), null);
        }
        log.info("plan_route 增量重排: 用户={}, 会话={}, POI数={}, days={}, 城市={}",
                prep.userId(), sessionId, prep.pois().size(), prep.days(), prep.destination());
        try {
            if (prep.fixture() && Boolean.TRUE.equals(ctx.get("assessment.toolFailure"))) {
                return new IncrementalRouteResult("ERROR: FIXTURE injected external tool failure", null);
            }
            RoutePlannerServiceImpl planner = prep.fixture()
                    ? com.travelscope.assessment.FixtureServices.planner(taskWorkspace) : routePlannerService;
            return new IncrementalRouteResult(null, planner.planAndWriteIncremental(
                    prep.userId(), sessionId, prep.pois(), prep.constraints(),
                    prep.destination(), previous));
        } catch (Exception e) {
            log.warn("plan_route 增量重排失败: 用户={}, 会话={}, 原因={}",
                    prep.userId(), sessionId, e.getMessage());
            return new IncrementalRouteResult("ERROR: 路线增量重排失败: " + e.getMessage(), null);
        }
    }

    /**
     * 行程草案组装（卡 C：LLM 产结构字段 + 代码渲染 markdownBody + 直接落盘，零 LLM 手写全文）。
     * 读 route_plan/poi_shortlist，LLM 一次产 ItineraryDraft 结构字段（days/budget/notes），
     * ItineraryAssembler 读缓存（天气/酒店/车票）渲染 markdownBody 后落盘 itinerary_draft.json。
     */
    @Tool(description = "行程草案组装（卡 C：你只决策，代码拼装全文）。内部：①LLM 产结构字段"
            + "（days/items 的 time/activity/location、主题、预算、注意事项）②代码按 route_plan "
            + "铺时间线、插入来源标注、渲染 markdownBody ③直接落盘 itinerary_draft.json。"
            + "前置：已写 poi_shortlist.json 且跑过 plan_route；天气/酒店/车票工具已成功返回（缓存由编排层自动登记）。"
            + "你只需调一次拿到摘要，严禁自己手写整篇 itinerary_draft.json 的 markdownBody")
    public String assemble_itinerary(
            @ToolParam(name = "sessionId", description = "本轮会话 ID，使用委派说明中给出的值")
            String sessionId,
            RuntimeContext ctx) {
        return doAssembleItinerary(sessionId, ctx);
    }

    /** assemble_itinerary 主体（R2 起 revise 链走增量版 doAssembleItineraryIncremental，本方法为其全量兜底） */
    private String doAssembleItinerary(String sessionId, RuntimeContext ctx) {
        String userId = ctx.getUserId();
        try {
            // 前置：路线与 POI 契约必须已就绪
            var routePlan = taskWorkspace.readRoutePlan(userId, sessionId);
            if (routePlan.isEmpty()) {
                return "ERROR: 会话目录缺 route_plan.json——先调 plan_route 完成分日路线求解";
            }
            var intake = taskWorkspace.readIntakeResult(userId, sessionId);
            if (intake.isEmpty()) {
                return "ERROR: 会话目录缺 intake_result.json";
            }

            // ① LLM 产结构字段（不含 markdownBody）；失败代码内自动重试 1 次——
            // 惊动 planner 层重试要烧一整个 agent 轮次（conv-46 实测 ~20s），代码内重试只付模型时延
            com.travelscope.collab.ItineraryDraft skeleton = produceDraftSkeleton(
                    userId, sessionId, routePlan.get(), intake.get());
            if (skeleton == null) {
                log.info("assemble_itinerary 结构字段首次生成失败，代码内重试: 用户={}, 会话={}", userId, sessionId);
                skeleton = produceDraftSkeleton(userId, sessionId, routePlan.get(), intake.get());
            }
            if (skeleton == null) {
                return "ERROR: 草案结构字段生成失败（模型未返回有效结构，已自动重试 1 次），请重试一次";
            }

            // ② 读缓存（天气/酒店/车票）+ poi_shortlist 已核验 POI 集合 + intake 偏好，代码渲染。
            // 缓存原文是工具返回的原始 JSON（记账去 LLM 化后不再经 LLM 消化）——进组装器前
            // 必须过 TaskResultFormatter 转摘要，否则 raw JSON 直通最终方案上屏（2026-10-20 实证）
            ItineraryDraft draft = renderAndWriteDraft(userId, sessionId, skeleton,
                    routePlan.get(), intake.get());
            log.info("assemble_itinerary 已组装落盘: 用户={}, 会话={}, {} 天, markdownBody={} 字符",
                    userId, sessionId, draft.days().size(), draft.markdownBody().length());
            return "已组装并写入 itinerary_draft.json（" + draft.days().size()
                    + " 天，markdownBody " + draft.markdownBody().length()
                    + " 字符，代码渲染）。核对本摘要无误后直接进入第 5 步一行收尾，严禁 read_file 复查草案文件。";
        } catch (Exception e) {
            log.warn("assemble_itinerary 失败: 用户={}, 会话={}, 原因={}", userId, sessionId, e.getMessage());
            return "ERROR: 草案组装失败: " + e.getMessage();
        }
    }

    /**
     * 组装公共收尾（全量/增量共用）：读缓存（TaskResultFormatter 转摘要）→
     * ItineraryAssembler 渲染 + 防幻觉 + 偏好校验 → 落盘 itinerary_draft.json。
     */
    private ItineraryDraft renderAndWriteDraft(String userId, String sessionId,
                                               ItineraryDraft skeleton, RoutePlan routePlan,
                                               IntakeResult intake) {
        String weather = com.travelscope.service.TaskResultFormatter.formatWeather(
                taskResultCache.get(userId, sessionId,
                        com.travelscope.service.TaskResultCache.TaskType.WEATHER));
        String hotel = com.travelscope.service.TaskResultFormatter.formatHotel(
                taskResultCache.get(userId, sessionId,
                        com.travelscope.service.TaskResultCache.TaskType.HOTEL));
        String train = com.travelscope.service.TaskResultFormatter.formatTrain(
                taskResultCache.get(userId, sessionId,
                        com.travelscope.service.TaskResultCache.TaskType.TRAIN));
        if (train == null) {
            train = com.travelscope.service.TaskResultFormatter.formatTrain(
                    taskResultCache.get(userId, sessionId,
                            com.travelscope.service.TaskResultCache.TaskType.FLIGHT));
        }
        // 防幻觉与偏好硬约束的校验输入
        PoiShortlist shortlist = taskWorkspace.readPoiShortlist(userId, sessionId).orElse(null);
        List<String> preferences = extractPreferences(intake);
        ItineraryDraft draft = itineraryAssembler.assemble(
                skeleton, routePlan, weather, hotel, train, shortlist, preferences);
        taskWorkspace.writeItineraryDraft(userId, sessionId, draft);
        return draft;
    }

    /**
     * R2 增量重组装（revise 链）：LLM 只对变化天重产结构字段（prompt 只含变化天路线 +
     * 旧草案未变天条目作预算参考），未变天由代码从旧草案逐字沿用——不信模型抄写。
     * 增量骨架失败/变化天缺失 → 逐级降级（重试 1 次 → 全量 doAssembleItinerary 兜底）。
     */
    private String doAssembleItineraryIncremental(String sessionId, RuntimeContext ctx,
                                                  Set<Integer> changedDays) {
        String userId = ctx.getUserId();
        try {
            var routePlan = taskWorkspace.readRoutePlan(userId, sessionId);
            if (routePlan.isEmpty()) {
                return doAssembleItinerary(sessionId, ctx);  // 防御：revise 已落盘，理论不可达
            }
            var intake = taskWorkspace.readIntakeResult(userId, sessionId);
            if (intake.isEmpty()) {
                return doAssembleItinerary(sessionId, ctx);
            }
            var oldDraftOpt = taskWorkspace.readItineraryDraft(userId, sessionId);
            if (oldDraftOpt.isEmpty()) {
                return doAssembleItinerary(sessionId, ctx);  // 无旧草案（首排后被外删等）→ 全量
            }
            ItineraryDraft oldDraft = oldDraftOpt.get();

            // ① 增量骨架：只产变化天结构 + 全局 budget/notes；失败代码内重试 1 次
            ItineraryDraft partial = produceDraftSkeletonIncremental(
                    userId, sessionId, routePlan.get(), intake.get(), changedDays, oldDraft);
            if (partial == null) {
                log.info("增量骨架首次生成失败，代码内重试: 用户={}, 会话={}", userId, sessionId);
                partial = produceDraftSkeletonIncremental(
                        userId, sessionId, routePlan.get(), intake.get(), changedDays, oldDraft);
            }
            ItineraryDraft skeleton = partial == null ? null
                    : mergeIncrementalSkeleton(oldDraft, partial, changedDays);
            if (skeleton == null) {
                log.info("增量骨架不完整（变化天缺失），降级全量重组装: 会话={}", sessionId);
                return doAssembleItinerary(sessionId, ctx);
            }

            // ② 渲染 + 校验 + 落盘：与全量路径共用（防幻觉/偏好校验/缓存摘要全保留，质量门不缩水）
            ItineraryDraft draft = renderAndWriteDraft(userId, sessionId, skeleton,
                    routePlan.get(), intake.get());
            log.info("增量重组装已落盘: 用户={}, 会话={}, 变化天={}, 其余天沿用旧草案, markdownBody={} 字符",
                    userId, sessionId, changedDays, draft.markdownBody().length());
            return "已增量重组装并写入 itinerary_draft.json（重排天 " + joinDayNumbers(changedDays)
                    + "，其余天沿用原草案条目）。请重新评审。";
        } catch (Exception e) {
            log.warn("增量重组装失败（降级全量）: 用户={}, 会话={}, 原因={}",
                    userId, sessionId, e.getMessage());
            return doAssembleItinerary(sessionId, ctx);
        }
    }

    /**
     * R2 增量骨架合并（纯函数，包内可测）：未变天从旧草案逐字沿用（代码级，不信模型抄写），
     * 变化天取增量骨架对应天；增量骨架缺失任一变化天 → 返回 null（调用方降级全量——
     * 旧草案该天的 POI 已变，不可沿用）。全局 budget/notes/pendingConfirm 取增量骨架。
     */
    static ItineraryDraft mergeIncrementalSkeleton(ItineraryDraft oldDraft, ItineraryDraft partial,
                                                   Set<Integer> changedDays) {
        Map<Integer, ItineraryDraft.DraftDay> partialByDay = new HashMap<>();
        for (ItineraryDraft.DraftDay d : partial.days()) {
            partialByDay.put(d.day(), d);
        }
        for (Integer day : changedDays) {
            if (!partialByDay.containsKey(day)) {
                return null;
            }
        }
        List<ItineraryDraft.DraftDay> merged = new ArrayList<>();
        Set<Integer> takenDays = new LinkedHashSet<>();
        for (ItineraryDraft.DraftDay oldDay : oldDraft.days()) {
            ItineraryDraft.DraftDay fresh = partialByDay.get(oldDay.day());
            takenDays.add(oldDay.day());
            merged.add(fresh != null ? fresh : oldDay);
        }
        // 增量骨架带旧草案没有的天号（模型越权产出，理论不该有）：按天号补挂保守接受
        partial.days().stream()
                .filter(d -> !takenDays.contains(d.day()))
                .sorted(java.util.Comparator.comparing(ItineraryDraft.DraftDay::day))
                .forEach(merged::add);
        return new ItineraryDraft(merged, partial.budget(), partial.notes(),
                partial.pendingConfirm(), partial.markdownBody());
    }

    /** R2 增量骨架：LLM 只产变化天的 days 结构 + 全局 budget/notes/pendingConfirm */
    private ItineraryDraft produceDraftSkeletonIncremental(String userId, String sessionId,
                                                           RoutePlan routePlan, IntakeResult intake,
                                                           Set<Integer> changedDays,
                                                           ItineraryDraft oldDraft) {
        List<RoutePlan.RouteDay> changedRoute = routePlan.days().stream()
                .filter(d -> changedDays.contains(d.day())).toList();
        List<ItineraryDraft.DraftDay> keptOldDays = oldDraft.days().stream()
                .filter(d -> !changedDays.contains(d.day())).toList();
        String prompt = "行程草案增量修订：部分天的路线已更新，只重新生成指定天的结构字段。\n\n"
                + "【需重新生成的天（新路线，紧凑视图）】\n" + compactRouteDaysView(changedRoute)
                + "\n\n【保持不变的天的既有草案条目（仅供预算与连贯性参考，禁止改动、禁止输出这些天）】\n"
                + JSON.toJSONString(keptOldDays)
                + "\n\n【用户需求】\n" + compactIntakeView(intake)
                + "\n\n输出 ItineraryDraft 结构字段：days 数组只包含需重新生成的天"
                + "（day 字段用对应天号，每项含 time（形如 09:00-11:30）/activity/location/theme），"
                + "外加全局 budget（须按全部天数估算）、notes、pendingConfirm。"
                + "名称/时间/价格必须与输入一致，不编造；markdownBody 填占位「组装中」。";
        RuntimeContext callCtx = RuntimeContext.builder()
                .userId("itinerary-assembler").sessionId("itinerary-assemble-" + sessionId).build();
        var agent = io.agentscope.core.ReActAgent.builder()
                .name("itinerary-assembler")
                .sysPrompt("你是行程结构整理器（增量模式）。只为指定天生成结构字段，"
                        + "budget 需覆盖参考条目在内的全部天数；不要输出未变化的天。"
                        + "markdownBody 填占位「组装中」。")
                .model(chatModel)
                .maxIters(2)
                .build();
        try {
            var reply = agent.call(prompt, ItineraryDraft.class, callCtx)
                    .block(java.time.Duration.ofSeconds(60));
            if (reply == null) {
                return null;
            }
            try {
                return reply.getStructuredData(ItineraryDraft.class);
            } catch (Exception ignored) {
                String text = reply.getTextContent();
                if (text != null && text.contains("\"days\"")) {
                    com.alibaba.fastjson2.JSONObject json = com.alibaba.fastjson2.JSON.parseObject(
                            text.replaceAll("(?s).*?(\\{.*\\}).*", "$1"));
                    json.putIfAbsent("markdownBody", "组装中");
                    json.putIfAbsent("schemaVersion", 4);
                    return json.toJavaObject(ItineraryDraft.class);
                }
            }
        } catch (Exception e) {
            log.warn("增量骨架生成异常: {}", e.getMessage());
        }
        return null;
    }

    /** 天号集合拼接（升序、「、」分隔），revise 返回与日志披露用 */
    private static String joinDayNumbers(Set<Integer> days) {
        return days.stream().sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining("、"));
    }

    /**
     * POI 重选决策代码化（卡 E：reviewer 只传加/删 POI 名，代码补字段+重排+重组装）。
     * ① 按名删 removePoiNames；② 对每个 addPoiNames 名调 searchPois 补硬字段
     * （lng/lat/openHours/source）构造 PoiItem 加入；③ 写回 poi_shortlist.json；
     * ④ R2 增量重排：有旧 route_plan 时未变天（POI 集合指纹全等）字节级复用，
     * 矩阵经对级缓存只补查涉及对；⑤ R2 增量重组装：只对变化天重产结构字段。
     */
    @Tool(description = "POI 重选（卡 E：你只给加/删的 POI 名，代码补字段并重排重组装，零手写）。"
            + "检出偏好/POI 缺失时调用：addPoiNames 传要补的 POI 名（如「同里古镇」），"
            + "removePoiNames 传要删的 POI 名（可空）。内部自动补坐标/开放时段、写回清单、"
            + "增量重排路线（未变天沿用原解）并重组装 itinerary_draft.json，"
            + "返回会披露重排/沿用的天号。严禁自己 write_collab_file 重写草案")
    public String revise_poi_selection(
            @ToolParam(name = "sessionId", description = "本轮会话 ID，使用委派说明中给出的值")
            String sessionId,
            @ToolParam(name = "addPoiNames", description = "要新增的 POI 名，逗号分隔（如「同里古镇」），可空",
                    required = false)
            String addPoiNames,
            @ToolParam(name = "removePoiNames", description = "要删除的 POI 名，逗号分隔，可空",
                    required = false)
            String removePoiNames,
            RuntimeContext ctx) {
        String userId = ctx.getUserId();
        try {
            var shortlistOpt = taskWorkspace.readPoiShortlist(userId, sessionId);
            if (shortlistOpt.isEmpty()) {
                return "ERROR: 会话目录缺 poi_shortlist.json——规划阶段未产出 POI 清单";
            }
            var intake = taskWorkspace.readIntakeResult(userId, sessionId);
            String destination = intake.map(i -> i.fields().destination() == null
                    ? null : i.fields().destination().value()).orElse(null);
            if (destination == null || destination.isBlank()) {
                return "ERROR: intake_result.json 中目的地缺失，无法补 POI 字段";
            }

            List<com.travelscope.collab.PoiShortlist.PoiItem> pois =
                    new java.util.ArrayList<>(shortlistOpt.get().pois());

            // ① 删除
            List<String> removed = new java.util.ArrayList<>();
            for (String name : splitNames(removePoiNames)) {
                boolean hit = pois.removeIf(p -> p.name() != null && p.name().contains(name));
                if (hit) {
                    removed.add(name);
                }
            }

            // ② 新增（硬字段补全）
            List<String> added = new java.util.ArrayList<>();
            List<String> failed = new java.util.ArrayList<>();
            for (String name : splitNames(addPoiNames)) {
                com.travelscope.collab.PoiShortlist.PoiItem item = resolvePoiItem(destination, name);
                if (item == null) {
                    failed.add(name);
                } else if (pois.stream().noneMatch(p -> p.name().equals(item.name()))) {
                    pois.add(item);
                    added.add(item.name());
                }
            }

            if (removed.isEmpty() && added.isEmpty()) {
                return "ERROR: 未做任何变更" + (failed.isEmpty() ? "" : "（补字段失败: " + failed + "）")
                        + "——请检查 POI 名是否正确";
            }

            // ③ 写回 poi_shortlist.json
            com.travelscope.collab.PoiShortlist updated = new com.travelscope.collab.PoiShortlist(
                    shortlistOpt.get().destination(),
                    shortlistOpt.get().retrievalNote() + "｜reviewer 修订 +" + added.size() + " -" + removed.size(),
                    pois);
            taskWorkspace.writePoiShortlist(userId, sessionId, updated);

            // ④ R2 增量重排：旧 route_plan 在覆盖前先取——未变天（POI 集合指纹全等）字节级复用，
            //    矩阵经对级缓存只补查涉及对（17 节点增删 1 点：136 对 ≈48s → 32 对 ≈11s）
            Optional<RoutePlan> previous = taskWorkspace.readRoutePlan(userId, sessionId);
            Set<Integer> changedDays = null;
            Set<Integer> reusedDays = null;
            if (previous.isPresent() && !previous.get().days().isEmpty()) {
                IncrementalRouteResult inc = planRouteIncremental(sessionId, ctx, previous.get());
                if (inc.outcome() == null) {
                    return "ERROR: POI 已更新但路线重排失败: " + inc.error();
                }
                changedDays = inc.outcome().changedDays();
                reusedDays = inc.outcome().reusedDays();
            } else {
                String routeResult = planRouteInternal(sessionId, null, null, ctx, false);
                if (routeResult.startsWith("ERROR")) {
                    return "ERROR: POI 已更新但路线重排失败: " + routeResult;
                }
            }

            // ⑤ R2 增量重组装：只对变化天重产结构字段，未变天沿用旧草案条目（代码级合并）
            String assembleResult = changedDays == null
                    ? doAssembleItinerary(sessionId, ctx)
                    : doAssembleItineraryIncremental(sessionId, ctx, changedDays);
            if (assembleResult.startsWith("ERROR")) {
                return "ERROR: POI 已更新并重排路线，但重组装失败: " + assembleResult;
            }
            log.info("revise_poi_selection: 用户={}, 会话={}, +{} -{}, 失败={}, 重排天={}, 沿用天={}",
                    userId, sessionId, added, removed, failed, changedDays, reusedDays);
            return "POI 修订完成：新增 " + added + "，删除 " + removed
                    + (failed.isEmpty() ? "" : "，补字段失败 " + failed)
                    + (changedDays == null ? "" : "；增量重排：第 " + joinDayNumbers(changedDays)
                            + " 天重排" + (reusedDays == null || reusedDays.isEmpty() ? ""
                            : "，第 " + joinDayNumbers(reusedDays) + " 天沿用原路线（输入未变）"))
                    + "；已重组装 itinerary_draft.json，请重新评审。";
        } catch (Exception e) {
            log.warn("revise_poi_selection 失败: 用户={}, 会话={}, 原因={}", userId, sessionId, e.getMessage());
            return "ERROR: POI 修订失败: " + e.getMessage();
        }
    }

    /** 逗号/顿号分隔的 POI 名列表拆分（去空） */
    private List<String> splitNames(String names) {
        if (names == null || names.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(names.split("[,，、]"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** 硬字段补全：按目的地+POI 名调 searchPois，取第一条构造 PoiItem；查不到返回 null */
    private com.travelscope.collab.PoiShortlist.PoiItem resolvePoiItem(String destination, String poiName) {
        try {
            String json = attractionTool.searchPois(destination, poiName, 5);
            com.alibaba.fastjson2.JSONObject result = com.alibaba.fastjson2.JSON.parseObject(json);
            com.alibaba.fastjson2.JSONArray attractions = result.getJSONArray("attractions");
            if (attractions == null || attractions.isEmpty()) {
                return null;
            }
            // 优先名字最匹配的一条
            com.alibaba.fastjson2.JSONObject best = null;
            for (int i = 0; i < attractions.size(); i++) {
                var a = attractions.getJSONObject(i);
                if (a.getString("name") != null && a.getString("name").contains(poiName)) {
                    best = a;
                    break;
                }
            }
            if (best == null) {
                best = attractions.getJSONObject(0);
            }
            String name = best.getString("name");
            String location = best.getString("location"); // "lng,lat"
            Double lng = null, lat = null;
            if (location != null && location.contains(",")) {
                String[] parts = location.split(",");
                lng = Double.parseDouble(parts[0].trim());
                lat = Double.parseDouble(parts[1].trim());
            }
            String source = best.getString("source") == null ? "高德" : best.getString("source");
            return new com.travelscope.collab.PoiShortlist.PoiItem(
                    name, lng, lat, best.getDouble("rating"),
                    best.getString("openHours"), best.getInteger("durationMin") == null
                            ? 120 : best.getInteger("durationMin"),
                    source, "reviewer 修订补入");
        } catch (Exception e) {
            log.warn("POI 硬字段补全失败: {}/{}, 原因={}", destination, poiName, e.getMessage());
            return null;
        }
    }

    /** 从 intake_result.json 抽取 preference 偏好关键词（逗号/顿号/空格分隔，去空） */
    private List<String> extractPreferences(com.travelscope.collab.IntakeResult intake) {
        try {
            var fv = intake.fields().preference();
            if (fv == null || fv.value() == null || fv.value().isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(fv.value().split("[,，、\\s]+"))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** LLM 一次产 ItineraryDraft 结构字段（复刻 RequirementExtractor 合成工具路径，markdownBody 用占位待代码渲染） */
    /**
     * L2① 组装 prompt 紧凑视图：routePlan 只保留 LLM 产结构字段所需的最小信息
     * （剥 solverMeta/坐标/transitFromPrev/通勤总计等渲染无关字段——对齐官方
     * "smallest set of high-signal tokens"）。包私有静态便于单测锚定。
     */
    static String compactRoutePlanView(RoutePlan routePlan) {
        return compactRouteDaysView(routePlan.days());
    }

    /** 天级紧凑视图（增量重组装的变化天子集同用） */
    static String compactRouteDaysView(List<RoutePlan.RouteDay> routeDays) {
        com.alibaba.fastjson2.JSONArray days = new com.alibaba.fastjson2.JSONArray();
        for (RoutePlan.RouteDay d : routeDays) {
            com.alibaba.fastjson2.JSONArray items = new com.alibaba.fastjson2.JSONArray();
            for (RoutePlan.RoutePoi p : d.pois()) {
                com.alibaba.fastjson2.JSONObject o = new com.alibaba.fastjson2.JSONObject();
                o.put("poi", p.name());
                o.put("time", (p.startTime() == null ? "" : p.startTime())
                        + "-" + (p.endTime() == null ? "" : p.endTime()));
                if (p.commuteMinutesFromPrev() != null) {
                    o.put("commuteMin", p.commuteMinutesFromPrev());
                }
                items.add(o);
            }
            com.alibaba.fastjson2.JSONObject day = new com.alibaba.fastjson2.JSONObject();
            day.put("day", d.day());
            day.put("items", items);
            days.add(day);
        }
        return days.toJSONString();
    }

    /**
     * L2① intake 紧凑视图：只传筛选/预算/主题所需核心字段
     * （FieldValue 包装剥离取 value，空值键省略）。
     */
    static String compactIntakeView(com.travelscope.collab.IntakeResult intake) {
        com.alibaba.fastjson2.JSONObject o = new com.alibaba.fastjson2.JSONObject();
        var f = intake.fields();
        putIfPresent(o, "destination", f.destination());
        putIfPresent(o, "days", f.days());
        putIfPresent(o, "startDate", f.startDate());
        putIfPresent(o, "fromCity", f.fromCity());
        putIfPresent(o, "budget", f.budget());
        putIfPresent(o, "preference", f.preference());
        return o.toJSONString();
    }

    private static void putIfPresent(com.alibaba.fastjson2.JSONObject o, String key,
                                     com.travelscope.collab.IntakeResult.FieldValue fv) {
        if (fv != null && fv.value() != null && !fv.value().isBlank()) {
            o.put(key, fv.value());
        }
    }

    private com.travelscope.collab.ItineraryDraft produceDraftSkeleton(
            String userId, String sessionId,
            com.travelscope.collab.RoutePlan routePlan,
            com.travelscope.collab.IntakeResult intake) {
        // L2① 消化结果代码化：routePlan/intake 以紧凑视图进 prompt（渲染无关字段不过 LLM）
        String prompt = "基于以下分日路线生成行程草案的结构字段（days/items 的 time/activity/location、"
                + "每日主题 theme、预算 budget、注意事项 notes、待确认 pendingConfirm）。"
                + "不要写 markdown 正文，只产结构字段。\n\n分日路线：\n"
                + compactRoutePlanView(routePlan)
                + "\n\n用户需求：\n" + compactIntakeView(intake);
        RuntimeContext callCtx = RuntimeContext.builder()
                .userId("itinerary-assembler").sessionId("itinerary-assemble-" + sessionId).build();
        var agent = io.agentscope.core.ReActAgent.builder()
                .name("itinerary-assembler")
                .sysPrompt("你是行程结构整理器。根据分日路线输出 ItineraryDraft 的结构字段："
                        + "days（每项 time 形如 09:00-11:30、activity、location）、"
                        + "budget（currency=CNY、totalEstimate、breakdown）、notes、pendingConfirm。"
                        + "名称/时间/价格必须与输入一致，不编造。markdownBody 填占位「组装中」。")
                .model(chatModel)
                .maxIters(2)
                .build();
        try {
            var reply = agent.call(prompt, com.travelscope.collab.ItineraryDraft.class, callCtx)
                    .block(java.time.Duration.ofSeconds(60));
            if (reply == null) {
                return null;
            }
            try {
                return reply.getStructuredData(com.travelscope.collab.ItineraryDraft.class);
            } catch (Exception ignored) {
                // 结构化元数据缺失 → 纯文本兜底解析
                String text = reply.getTextContent();
                if (text != null && text.contains("\"days\"")) {
                    com.alibaba.fastjson2.JSONObject json = com.alibaba.fastjson2.JSON.parseObject(
                            text.replaceAll("(?s).*?(\\{.*\\}).*", "$1"));
                    json.putIfAbsent("markdownBody", "组装中");
                    json.putIfAbsent("schemaVersion", 4);
                    return json.toJavaObject(com.travelscope.collab.ItineraryDraft.class);
                }
            }
        } catch (Exception e) {
            log.warn("草案结构字段生成异常: {}", e.getMessage());
        }
        return null;
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
        // 名单制硬锁（卡③）：poi_shortlist 必须走 select_poi_shortlist——候选池校验+硬字段补齐
        // 由代码保证，手写 JSON 是幻觉入口（conv-55 实测 LLM 绕过名单制手写）
        if ("poi_shortlist.json".equals(fileName)) {
            return "ERROR: poi_shortlist.json 不接受手写——请改用 select_poi_shortlist(sessionId, names, "
                    + "durationMins, reasons) 传选中名单（必须全部来自候选池），代码校验+补字段+落盘";
        }
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
