package com.travelscope.agent;

import com.travelscope.agent.tools.PoiRagTools;
import com.travelscope.service.RagServiceImpl;
import com.travelscope.service.TaskResultCache;
import com.travelscope.service.TaskResultCache.TaskType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 回炉成本实测：全量重做（v3.1 语义）vs 分段回炉（FR-S08 v3.2 + FR-S14 / B2）。
 * <p>
 * 同一需求（杭州+上海双城 2 日、预算 700 元）、同一起点（上一版草案质检 失败段=2
 * 路线问题）、同一终点（重审 PASS 收尾），分别执行两条回炉路径并测量「回炉执行段」成本：
 * </p>
 * <ul>
 *   <li>全量重做（conv-9201）：任务结果缓存全部失效（TaskResultCache 零登记，等价清掉
 *       taskresult:*），协作目录保留首轮产物与 FAIL 报告，委派要求从头重新执行第 1~4 步
 *       ——模拟 v3.1「整个 Planner 重做、好结果全部白烧」</li>
 *   <li>分段回炉（conv-9202）：四类缓存全部保留，按 B2 提示词只重跑失败段对应的
 *       route-optimizer（POI 池走缓存复用），planner 重组装后重审</li>
 * </ul>
 * <p>
 * 指标口径：总耗时 = planner streamEvents 全程（nanoTime，ctx 设 force_sync 同步等待，
 * 计时口径稳定）；LLM 调用次数 = AgentTraceMiddleware 的 PRE_REASONING 日志条数
 * （每个 Agent 每轮模型调用一条，覆盖 planner + 子 Agent 全树）。两路径的重审均为
 * 脚本化 PASS 裁决桩（成本对称），不含共同的首次 FAIL 评审（两路径同价，不计入差值）。
 * 外部 API 用确定性桩（复用 {@link PlannerSegmentReworkRealApiTest} 的
 * PlannerStubs / SpawnRecorder / ThrowingRedisTemplate / RecordingAppender——同一套桩
 * 保证两组可比）。结论写入 docs/fix-record-2026-10-04-回炉成本实测.md。
 * </p>
 */
@EnabledIfEnvironmentVariable(named = "API_KEY", matches = "sk-.+")
class ReworkCostBenchmarkRealApiTest {

    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(15);

    private static final String USER_ID = "1";

    /** AgentTraceMiddleware 的 PRE_REASONING = 一次模型调用（含子 Agent） */
    private static final String TRACE_LOGGER =
            "io.agentscope.harness.agent.middleware.AgentTraceMiddleware";
    private static final Pattern CALL_PATTERN = Pattern.compile("\\[([^\\]]+)\\] PRE_REASONING");

    // ==================== fixture（与 B2 场景1 同需求、同段2 fail 起点） ====================

    private static final String INTAKE = """
            # 需求收集结果（intake-agent 转交）

            - 目的地: 杭州 + 上海双城游（以杭州为主，上海须含外滩）
            - 日期与天数: 2026-10-25 ~ 2026-10-26，共 2 天
            - 同行人: 1 人
            - 总预算: 700 元（含沪杭高铁往返、市内交通/门票/住宿/餐饮）
            - 住宿要求: 经济型酒店即可
            - 偏好: 自然风光 + 人文历史
            - 大交通: 沪杭高铁往返共 2 程
            - 状态: CONFIRMED
            """;

    private static final String POI_SHORTLIST = """
            # 杭州景点候选清单（poi_shortlist）

            | # | 景点 | 坐标（经度,纬度） | 门票 | 建议时长 | 开放时间 |
            |---|---|---|---|---|---|
            | 1 | 西湖（断桥—白堤—苏堤） | 120.14912,30.25941 | 免费 | 2.5小时 | 全天开放 |
            | 2 | 河坊街 | 120.17156,30.24472 | 免费 | 1.5小时 | 全天开放 |
            | 3 | 灵隐寺 | 120.09961,30.24088 | 45元 | 2小时 | 07:00-18:00 |
            | 4 | 西溪国家湿地公园 | 120.06280,30.26920 | 80元 | 3小时 | 08:00-17:30 |
            | 5 | 外滩（上海） | 121.49032,31.23490 | 免费 | 1.5小时 | 全天开放 |

            说明：候选以杭州为主，外滩对应双城游需求中的上海段。
            """;

    /** 首轮产出的烂路线（段2 fail 的肇因）：第1天 西湖→外滩(上海)→河坊街 跨城往返 */
    private static final String BAD_ROUTE_PLAN = """
            # 分日路线方案（route_plan）

            ## 第1天（2026-10-25 周日）
            | 时段 | 安排 | 点间通勤 |
            |---|---|---|
            | 08:30-11:00 | 西湖（断桥—白堤—苏堤） | - |
            | 11:00-14:00 | 跨城前往外滩（杭州东→上海虹桥→地铁至外滩） | 高铁+地铁 约180分钟 |
            | 14:00-15:30 | 外滩 | - |
            | 15:30-18:30 | 跨城返回杭州（外滩→上海虹桥→杭州东→市区） | 高铁+地铁 约185分钟 |
            | 18:30-19:30 | 河坊街晚餐夜逛 | 步行 |

            ## 第2天（2026-10-26 周一）
            | 时段 | 安排 | 点间通勤 |
            |---|---|---|
            | 06:30-07:35 | 退房+公交前往灵隐寺 | 公交 约65分钟 |
            | 07:35-09:35 | 灵隐寺 | - |
            | 09:35-10:15 | 公交前往西溪湿地 | 公交 约40分钟 |
            | 10:15-13:15 | 西溪国家湿地公园 | - |

            调优过程：按候选清单顺序串联，外滩往返压缩在第1天内完成。
            """;

    private static final String ITINERARY_DRAFT = """
            # 杭州+上海双城2日行程草案（2026-10-25 ~ 10-26，1人，预算700元）

            ## 第1天（10月25日 周日）
            | 时间 | 行程 | 地点 | 费用 | 点间通勤 |
            |---|---|---|---|---|
            | 08:30-11:00 | 西湖环湖（断桥→白堤→苏堤） | 西湖 | 免费 | - |
            | 11:00-14:00 | 高铁+地铁前往上海外滩 | 途中 | 73元 | 约180分钟 |
            | 14:00-15:30 | 外滩漫步 | 外滩 | 免费 | - |
            | 15:30-18:30 | 高铁+地铁返回杭州 | 途中 | 73元 | 约185分钟 |
            | 18:30-19:30 | 河坊街晚餐+夜逛 | 河坊街 | 60元 | 步行 |
            | 20:00 | 入住汉庭酒店（西湖店） | 上城区 | 180元/晚 | 公交 约10分钟 |

            ## 第2天（10月26日 周一）
            | 时间 | 行程 | 地点 | 费用 | 点间通勤 |
            |---|---|---|---|---|
            | 06:30-07:35 | 退房+公交前往灵隐寺 | 途中 | - | 公交 约65分钟 |
            | 07:35-09:35 | 灵隐寺 | 灵隐寺 | 45元 | - |
            | 09:35-10:15 | 公交前往西溪湿地 | 途中 | - | 公交 约40分钟 |
            | 10:15-13:15 | 西溪国家湿地公园 | 西溪湿地 | 80元 | - |
            | 13:15 | 返程 | - | - | 地铁 约30分钟 |

            ## 费用汇总（1人）
            - 住宿: 180元
            - 门票: 125元（灵隐寺45 + 西溪80）
            - 市内交通: 60元
            - 沪杭高铁往返: 146元（73元×2程）
            - 餐饮: 170元
            - 总计: 681元（预算700元内，结余19元）

            > 天气提示：两日晴，15~24℃，适合户外游览。
            """;

    /** 首审（流外，两路径共同发生、不计入差值）的段2 fail 质检报告，作为回炉起点 */
    private static final String FAIL_REPORT = """
            # 质检报告：不通过

            - 总分: 62/100（通过线 80）
            - 失败段: 2（路线连贯性+时间密度：第1天 西湖→外滩→河坊街 单日跨城往返通勤合计约 365 分钟，远超单日上限）

            ## 分段结论

            | 段 | 审核项 | 结论 | 问题 |
            |---|---|---|---|
            | 1 | POI 有效性 | pass | 所有 POI 真实存在，营业时间与排期不冲突 |
            | 2 | 路线连贯性+时间密度 | fail | 第1天 杭州↔上海 两段跨城通勤 180+185=365 分钟，单日通勤严重超限 |
            | 3 | 预算+偏好匹配 | pass | 总计 681 元在预算 700 元内；双城与偏好覆盖完整 |

            - 核验记录: getTransitRoute(西湖, 外滩) 实测约 210 分钟，超 90 分钟上限，与草案声明 180 分钟不符

            ## 改进建议（给 planning-agent 的回炉指令）
            按失败段组织，每条标注对应段号，具体到改哪一天、哪一项：
            1. [段2] 重排路线消除单日跨城往返：把外滩调整为独立半天行程，或按就近聚类重新分日，单日通勤合计控制在 90 分钟内

            REVIEW_RESULT: FAIL 总分=62 失败段=2
            """;

    /** 重审裁决（两路径流内唯一一次送审 → PASS，成本对称） */
    private static final String PASS_VERDICT = """
            # 质检通过

            - 总分: 88/100（通过线 80，单维最低线 12，三段全 pass）

            ## 分段结论

            | 段 | 审核项 | 结论 |
            |---|---|---|
            | 1 | POI 有效性 | pass |
            | 2 | 路线连贯性+时间密度 | pass |
            | 3 | 预算+偏好匹配 | pass |

            - 核验声明: 重排后各段通勤均在 90 分钟内，与草案声明一致
            - 一句话总评: 分日结构合理，预算达标

            REVIEW_RESULT: PASS 总分=88
            """;

    private static final String BACKLOG_FRESH = """
            # 任务清单（task_backlog）

            | 任务ID | 优先级 | 类型 | 描述 | 状态 |
            |---|---|---|---|---|
            | T1 | P0 | weather-query | 查询杭州 10月25~26日天气预报 | PENDING |
            | T2 | P0 | hotel-search | 确认住宿酒店（按预算过滤） | PENDING |
            | T3 | P0 | attraction-search | 检索杭州景点候选，产出 poi_shortlist.md | PENDING |
            | T4 | P0 | route-planning | 分日路线调优，产出 route_plan.md | PENDING |
            | T5 | P1 | itinerary | 组装行程草案 itinerary_draft.md 并送质检 | PENDING |
            """;

    private static final String BACKLOG_FIRST_PASS_DONE = """
            # 任务清单（task_backlog）

            | 任务ID | 优先级 | 类型 | 描述 | 状态 |
            |---|---|---|---|---|
            | T1 | P0 | weather-query | 查询杭州 10月25~26日天气预报 | DONE |
            | T2 | P0 | hotel-search | 确认住宿酒店（按预算过滤） | DONE |
            | T3 | P0 | attraction-search | 检索杭州景点候选，产出 poi_shortlist.md | DONE |
            | T4 | P0 | route-planning | 分日路线调优，产出 route_plan.md | DONE |
            | T5 | P1 | itinerary | 组装行程草案 itinerary_draft.md 并送质检 | IN_PROGRESS |
            """;

    private static final String WEATHER_CACHE = """
            杭州 2026-10-25：晴，15~24℃；2026-10-26：晴转多云，16~23℃。适宜户外，早晚建议薄外套。""";

    private static final String HOTEL_CACHE = """
            汉庭酒店（杭州西湖店）｜经济型｜大床房 180元/晚｜上城区邮驿路，近西湖｜评分4.6""";

    /** 裁决回放桩（与 PlannerSegmentReworkRealApiTest 同款；双文件均为 PASS——流内唯一送审即重审） */
    private static final String SCRIPTED_REVIEWER_PROMPT = """
            你是 TravelScope 的质检结果回放桩（reviewer-agent，测试基础设施）。\
            你不做任何审核判断，只回放预设裁决。收到送审任务说明后严格按以下步骤执行，\
            除此之外不做任何事：

            1. 从任务说明中解析协作目录（形如 tasks/conv-xxxx）。
            2. 选文件：任务说明中含「第 2 次送审」或「第 3 次送审」字样 →\
            read_file 读取 {协作目录}/scripted_verdict_recheck.md；\
            否则 → read_file 读取 {协作目录}/scripted_verdict_first.md。
            3. 把读到的文件全文 write_file 落盘：内容以「# 质检报告：不通过」开头 →\
            写入 {协作目录}/review_report.md；以「# 质检通过」开头 →\
            写入 {协作目录}/review_passed.md。
            4. 最终回复：第一行原样输出该文件末尾的「REVIEW_RESULT: …」标记行，\
            之后给 1-2 句摘要。输出最终回复后不得再调用任何工具。
            """;

    // ==================== 实测 ====================

    record BenchResult(String label, long elapsedMs, Map<String, Long> callsByAgent,
                       long totalCalls, List<PlannerSegmentReworkRealApiTest.SpawnCall> spawns,
                       List<String> cacheHits) {
    }

    @Test
    @DisplayName("B3 回炉成本实测：全量重做（清缓存全部重跑） vs 分段回炉（缓存保留只重跑 route-optimizer）")
    void fullRedoVsSegmentedReworkCost() throws Exception {
        Path workspaceRoot = Files.createTempDirectory("rework-cost-bench-");
        try {
            BenchResult fullRedo = runPath(workspaceRoot, "conv-9201", true);
            BenchResult segmented = runPath(workspaceRoot, "conv-9202", false);
            printComparison(fullRedo, segmented);
        } finally {
            deleteRecursivelyBestEffort(workspaceRoot);
        }
    }

    /**
     * 执行一条回炉路径并测量。fullRedo=true：缓存零登记 + 委派要求完整重跑第 1~4 步；
     * false：四类缓存保留 + 委派要求按失败段局部回炉。
     */
    private BenchResult runPath(Path workspaceRoot, String sessionId, boolean fullRedo)
            throws Exception {
        Path taskDir = workspaceRoot.resolve(USER_ID).resolve("tasks").resolve(sessionId);
        Files.createDirectories(taskDir);
        // 两路径共同的回炉起点：首轮产物 + 段2 fail 质检报告；区别只在缓存与委派指令
        Map<String, String> files = new LinkedHashMap<>();
        files.put("task_backlog.md", fullRedo ? BACKLOG_FRESH : BACKLOG_FIRST_PASS_DONE);
        files.put("intake_done.md", INTAKE);
        files.put("poi_shortlist.md", POI_SHORTLIST);
        files.put("route_plan.md", BAD_ROUTE_PLAN);
        files.put("itinerary_draft.md", ITINERARY_DRAFT);
        files.put("review_report.md", FAIL_REPORT);
        files.put("scripted_verdict_first.md", PASS_VERDICT);
        files.put("scripted_verdict_recheck.md", PASS_VERDICT);
        for (Map.Entry<String, String> e : files.entrySet()) {
            Files.writeString(taskDir.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
        }

        // 全量重做 = 清掉 taskresult:*（新鲜缓存实例、零登记）；分段回炉 = 四类缓存保留
        TaskResultCache cache = new TaskResultCache(
                new PlannerSegmentReworkRealApiTest.ThrowingRedisTemplate());
        if (!fullRedo) {
            cache.register(USER_ID, sessionId, TaskType.WEATHER, "-", WEATHER_CACHE);
            cache.register(USER_ID, sessionId, TaskType.HOTEL, "-", HOTEL_CACHE);
            cache.register(USER_ID, sessionId, TaskType.POI, "-", POI_SHORTLIST);
            cache.register(USER_ID, sessionId, TaskType.ROUTE, "-", BAD_ROUTE_PLAN);
        }

        RagServiceImpl rag = Mockito.mock(RagServiceImpl.class);
        Mockito.when(rag.isAvailable()).thenReturn(false);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new PoiRagTools(rag, cache));
        toolkit.registerTool(new PlannerSegmentReworkRealApiTest.PlannerStubs());

        PlannerSegmentReworkRealApiTest.SpawnRecorder recorder =
                new PlannerSegmentReworkRealApiTest.SpawnRecorder();
        PlannerSegmentReworkRealApiTest.RecordingAppender traceAppender =
                new PlannerSegmentReworkRealApiTest.RecordingAppender();
        traceAppender.start();
        PlannerSegmentReworkRealApiTest.RecordingAppender cacheAppender =
                new PlannerSegmentReworkRealApiTest.RecordingAppender();
        cacheAppender.start();
        org.slf4j.Logger rawTraceLogger = LoggerFactory.getLogger(TRACE_LOGGER);
        org.slf4j.Logger rawCacheLogger = LoggerFactory.getLogger(TaskResultCache.class);
        ch.qos.logback.classic.Logger traceLogger =
                rawTraceLogger instanceof ch.qos.logback.classic.Logger tl ? tl : null;
        ch.qos.logback.classic.Logger cacheLogger =
                rawCacheLogger instanceof ch.qos.logback.classic.Logger cl ? cl : null;
        if (traceLogger != null) {
            traceLogger.addAppender(traceAppender);
        }
        if (cacheLogger != null) {
            cacheLogger.addAppender(cacheAppender);
        }

        String task = fullRedo
                ? "协作目录: tasks/" + sessionId + "（会话 ID: " + sessionId + "）\n"
                        + "上一版行程草案质检未通过（详见协作目录 review_report.md，失败段=2 路线问题），"
                        + "任务结果缓存已全部失效。\n"
                        + "请从头重新执行完整规划流程：第 1 步重新查询天气与酒店、"
                        + "第 2 步重新 spawn poi-research 检索景点候选并重新 spawn route-optimizer 排线、"
                        + "第 3 步重新组装行程草案、第 4 步质检送审（本次为第 2 次送审）。"
                : "协作目录: tasks/" + sessionId + "（会话 ID: " + sessionId + "）\n"
                        + "上一版行程草案质检未通过（详见协作目录 review_report.md，失败段=2 路线问题）。"
                        + "四类任务缓存（weather/hotel/poi/route）均已登记且有效。\n"
                        + "请按失败段局部回炉：复用未失败段成果，只重跑失败段对应的子任务，"
                        + "修订后完成第 2 次送审。";

        try {
            HarnessAgent planner = buildPlanner(workspaceRoot, toolkit, recorder);
            RuntimeContext ctx = RuntimeContext.builder()
                    .userId(USER_ID).sessionId(sessionId).build();
            ctx.put(IntentRouterMiddleware.CTX_COLLAB_DIR_KEY, "tasks/" + sessionId);
            // 同步等待 + 超时不转后台：计时口径稳定（与 B2 验收测试同款）
            ctx.put(AgentSpawnTool.CTX_FORCE_SYNC, Boolean.TRUE);
            ctx.put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 300);

            StringBuilder replyBuf = new StringBuilder();
            List<String> toolTimeline = new CopyOnWriteArrayList<>();
            long t0 = System.nanoTime();
            planner.streamEvents(new UserMessage(task), ctx)
                    .doOnNext(event -> {
                        if (event instanceof ToolResultEndEvent e) {
                            toolTimeline.add(e.getToolCallName());
                            replyBuf.setLength(0);
                        } else if (event instanceof TextBlockDeltaEvent e) {
                            replyBuf.append(e.getDelta());
                        }
                    })
                    .blockLast(RUN_TIMEOUT);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

            // LLM 调用计数：PRE_REASONING 每条 = 一次模型调用（含子 Agent 全树）
            Map<String, Long> callsByAgent = new LinkedHashMap<>();
            for (var event : traceAppender.events) {
                String msg = event.getFormattedMessage();
                if (msg == null) {
                    continue;
                }
                var m = CALL_PATTERN.matcher(msg);
                if (m.find()) {
                    callsByAgent.merge(m.group(1), 1L, Long::sum);
                }
            }
            List<String> cacheHits = cacheAppender.events.stream()
                    .map(var -> var.getFormattedMessage())
                    .filter(var -> var != null && var.startsWith("cache_hit="))
                    .map(var -> var.contains(" ")
                            ? var.substring(0, var.indexOf(' ')) : var)
                    .toList();
            long totalCalls = callsByAgent.values().stream().mapToLong(Long::longValue).sum();

            // ---- 测量有效性校验：路径未按协议走完则数字无效，直接判失败 ----
            long poiSpawns = count(recorder.spawns, PoiResearchAgent.AGENT_NAME);
            long routeSpawns = count(recorder.spawns, RouteOptimizerAgent.AGENT_NAME);
            long reviewerSpawns = count(recorder.spawns, ReviewerAgent.AGENT_NAME);
            assertEquals(1, reviewerSpawns,
                    (fullRedo ? "全量重做" : "分段回炉")
                            + "路径应恰有一次重审送审（PASS 收尾）。实际 spawn 序列: " + recorder.spawns);
            if (fullRedo) {
                assertTrue(poiSpawns >= 1 && routeSpawns >= 1,
                        "全量重做路径应重新 spawn poi-research 与 route-optimizer（缓存已清）。"
                                + " 实际 spawn 序列: " + recorder.spawns);
            } else {
                assertEquals(0, poiSpawns,
                        "分段回炉路径不得重新 spawn poi-research（POI 走缓存复用）。"
                                + " 实际 spawn 序列: " + recorder.spawns);
                assertTrue(routeSpawns >= 1,
                        "分段回炉路径应重新 spawn route-optimizer（段2 失败子任务）。"
                                + " 实际 spawn 序列: " + recorder.spawns);
                assertTrue(cacheHits.stream().anyMatch(h -> h.startsWith("cache_hit=poi")),
                        "分段回炉路径应出现 cache_hit=poi（FR-S14 复用锚点）。实际: " + cacheHits);
            }
            assertTrue(!replyBuf.toString().isBlank(), "planner 应有最终汇报");

            String label = fullRedo ? "全量重做（v3.1 语义：缓存清空，第1~4步全部重跑）"
                    : "分段回炉（v3.2+B2：缓存保留，只重跑 route-optimizer）";
            System.out.println("[BENCH] ===== " + label + " =====");
            System.out.println("[BENCH] 总耗时: " + elapsedMs + " ms");
            System.out.println("[BENCH] LLM 调用（PRE_REASONING 计数）: " + totalCalls
                    + "，分布: " + callsByAgent);
            System.out.println("[BENCH] spawn 序列: " + recorder.spawns);
            System.out.println("[BENCH] 工具调用数: " + toolTimeline.size() + "，cache_hit: " + cacheHits);
            System.out.println("[BENCH] planner 最终回复:\n" + replyBuf.toString().strip() + "\n");
            return new BenchResult(label, elapsedMs, callsByAgent, totalCalls,
                    List.copyOf(recorder.spawns), cacheHits);
        } finally {
            if (traceLogger != null) {
                traceLogger.detachAppender(traceAppender);
            }
            if (cacheLogger != null) {
                cacheLogger.detachAppender(cacheAppender);
            }
            traceAppender.stop();
            cacheAppender.stop();
        }
    }

    private static void printComparison(BenchResult fullRedo, BenchResult segmented) {
        long timeSavedPct = Math.round((fullRedo.elapsedMs() - segmented.elapsedMs()) * 100.0
                / fullRedo.elapsedMs());
        long callsSavedPct = Math.round((fullRedo.totalCalls() - segmented.totalCalls()) * 100.0
                / fullRedo.totalCalls());
        System.out.println("================ B3 回炉成本实测：对比结论 ================");
        System.out.println("口径：同一需求（杭州+上海双城2日，预算700元）、同一起点（段2 fail 质检报告），"
                + "测回炉执行段（不含两路径同价的首次 FAIL 评审，各含 1 次重审 PASS）。");
        System.out.println("指标：总耗时 ms；LLM 调用 = AgentTraceMiddleware PRE_REASONING 条数"
                + "（planner + 子 Agent 全树）。");
        System.out.println("------------------------------------------------------------");
        System.out.printf("%-12s %14s %12s%n", "路径", "总耗时(ms)", "LLM调用");
        System.out.printf("%-12s %14d %12d%n", "全量重做", fullRedo.elapsedMs(), fullRedo.totalCalls());
        System.out.printf("%-12s %14d %12d%n", "分段回炉", segmented.elapsedMs(), segmented.totalCalls());
        System.out.println("------------------------------------------------------------");
        System.out.println("耗时下降: " + timeSavedPct + "%，LLM 调用下降: " + callsSavedPct + "%"
                + "（FR-S14 验收线：≥50%）");
        System.out.println("全量重做调用分布: " + fullRedo.callsByAgent());
        System.out.println("分段回炉调用分布: " + segmented.callsByAgent());
    }

    /** 与 PlannerSegmentReworkRealApiTest 同构的 4-Agent 图（reviewer 为裁决回放桩） */
    private static HarnessAgent buildPlanner(Path workspaceRoot, Toolkit toolkit,
                                             PlannerSegmentReworkRealApiTest.SpawnRecorder recorder)
            throws IOException {
        SubagentDeclaration poiResearch = SubagentDeclaration.builder()
                .name(PoiResearchAgent.AGENT_NAME)
                .description("景点检索 Agent（检索员）：按目的地+天数+偏好多轮检索筛选景点候选，"
                        + "召回不足时换关键词重查，产出 poi_shortlist.md")
                .inlineAgentsBody(PoiResearchAgent.SYS_PROMPT)
                .model("qwen-plus")
                .maxIters(PoiResearchAgent.MAX_ITERS)
                .workspaceMode(WorkspaceMode.SHARED)
                .skills(List.of("attraction-search"))
                .build();

        SubagentDeclaration routeOptimizer = SubagentDeclaration.builder()
                .name(RouteOptimizerAgent.AGENT_NAME)
                .description("路线调优 Agent（排线员）：读 poi_shortlist.md，两两调路线工具算通勤，"
                        + "就近聚类分日 + 迭代调整再算，产出 route_plan.md 并自行登记缓存")
                .inlineAgentsBody(RouteOptimizerAgent.SYS_PROMPT)
                .model("qwen-plus")
                .maxIters(RouteOptimizerAgent.MAX_ITERS)
                .workspaceMode(WorkspaceMode.SHARED)
                .skills(List.of("route-planning"))
                .tools(List.of(
                        "getDrivingRoute",
                        "getTransitRoute",
                        "geocode",
                        "register_task_result",
                        "get_cached_task_result"))
                .build();

        SubagentDeclaration reviewer = SubagentDeclaration.builder()
                .name(ReviewerAgent.AGENT_NAME)
                .description("质量审阅 Agent（质检员）：对 itinerary_draft.md 做分段审核，"
                        + "通过写 review_passed.md，不通过写 review_report.md（含分段结论与改进建议，"
                        + "供 planner 按段回炉）")
                .inlineAgentsBody(SCRIPTED_REVIEWER_PROMPT)
                .model("qwen-plus")
                .maxIters(4)
                .workspaceMode(WorkspaceMode.SHARED)
                .build();

        DashScopeChatModel qwenPlus = DashScopeChatModel.builder()
                .apiKey(System.getenv("API_KEY"))
                .modelName("qwen-plus")
                .stream(true)
                .build();

        return HarnessAgent.builder()
                .name(ItineraryAgent.AGENT_NAME)
                .sysPrompt(ItineraryAgent.SYS_PROMPT)
                .model(qwenPlus)
                .modelResolver(name -> qwenPlus)
                .toolkit(toolkit)
                .maxIters(ItineraryAgent.MAX_ITERS)
                .enablePendingToolRecovery(true)
                .permissionContext(PermissionContextState.builder()
                        .mode(PermissionMode.BYPASS).build())
                .workspace(workspaceRoot.toString())
                .skillRepository(new ClasspathSkillRepository("skills"))
                .stateStore(new InMemoryAgentStateStore())
                .middlewares(List.of(recorder))
                .subagents(List.of(poiResearch, routeOptimizer, reviewer))
                .build();
    }

    private static long count(List<PlannerSegmentReworkRealApiTest.SpawnCall> spawns, String agentId) {
        return spawns.stream().filter(s -> s.agentId().equals(agentId)).count();
    }

    /** 尽力清理（harness 工作区句柄未释放时残留无害，B2 验收测试同款） */
    private static void deleteRecursivelyBestEffort(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // Windows 下句柄未释放，残留临时目录无害
                }
            });
        } catch (IOException ignored) {
            // 根目录不可访问，放弃清理
        }
    }
}
