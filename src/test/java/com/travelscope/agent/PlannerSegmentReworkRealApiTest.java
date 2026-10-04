package com.travelscope.agent;

import com.travelscope.agent.tools.PoiRagTools;
import com.travelscope.service.RagServiceImpl;
import com.travelscope.service.TaskResultCache;
import com.travelscope.service.TaskResultCache.TaskType;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
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
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Planner（ItineraryAgent）按失败段局部回炉真实验收测试（FR-S08 v3.2 / FR-S14 / B2）。
 * <p>
 * 不经 Spring 上下文，复刻 AgentConfig.buildPlannerAgent 的 4-Agent 图，外部 API 用
 * 确定性桩（天气/路线/地理/酒店/POI），沿用 ReviewerSegmentedReviewRealApiTest 的
 * 门控与自管临时目录模式。两场景均预置全部协作文件与四类缓存（模拟「首轮已完成」
 * 状态），以 master 委派口吻触发 planner 从第 4 步质检送审开始执行完整闭环：
 * </p>
 * <ul>
 *   <li>场景1（conv-9101）：预置跨城往返烂路线（route_plan 含 杭州→外滩→杭州
 *       两段 ~180 分钟通勤），裁决回放段2 fail → 回炉只重跑 route-optimizer（真实
 *       qwen-plus），POI 走缓存复用（FR-S14 锚点 cache_hit=poi），poi-research 不被 spawn</li>
 *   <li>场景2（conv-9102）：干净西湖小簇行程但预算整体超支（800 预算 / 1025 总计），
 *       裁决回放段3 fail → planner 自行修订草案（换酒店），不委派任何检索/排线子 Agent</li>
 * </ul>
 * <p>
 * <b>reviewer 为脚本化裁决回放桩</b>（读 scripted_verdict.md 按送审次数回放，格式复刻
 * ReviewerAgent 第六/七节契约）：B2 的验收对象是 planner 的按段路由，裁决的生产
 * （qwen-max 分段审核）已由 ReviewerSegmentedReviewRealApiTest 真实验收；真跑实证
 * （2026-10-04 六轮）路由逻辑全部正确、失败断言均源自真实裁决的段号漂移，故本测试
 * 将裁决固定为受控输入以保证可重复。观测通道：SpawnRecorder 中间件（仿
 * ReviewerRetryMiddleware 的 onActing 拦截）记录 agent_spawn（agent_id + 时间戳）；
 * Logback RecordingAppender 捕获 TaskResultCache 的 cache_hit={type} 日志。
 * </p>
 */
@EnabledIfEnvironmentVariable(named = "API_KEY", matches = "sk-.+")
class PlannerSegmentReworkRealApiTest {

    /** 4-Agent 图全链（planner+reviewer×2+route-optimizer）真实 LLM 调用，留足余量 */
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(30);

    private static final String USER_ID = "1";

    // ==================== 场景1：段2 fail（烂路线） ====================

    /** 跨城往返烂路线：第1天 西湖→外滩(上海)→河坊街，两段 ~180 分钟跨城通勤挤在同一天（超单日通勤上限） */
    private static final String SC1_ROUTE_PLAN = """
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

    private static final String SC1_POI_SHORTLIST = """
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

    private static final String SC1_ITINERARY_DRAFT = """
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

    private static final String SC1_INTAKE = """
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

    // ==================== 场景2：段3 fail（预算超支） ====================

    /** 干净连贯的市内路线：POI 全部聚在西湖—河坊街 3km 簇内，各段通勤 10~20 分钟（段2 无可挑剔） */
    private static final String SC2_ROUTE_PLAN = """
            # 分日路线方案（route_plan）

            ## 第1天（2026-10-25 周日）
            | 时段 | 安排 | 点间通勤 |
            |---|---|---|
            | 09:00-11:30 | 西湖环湖（断桥—白堤—孤山） | - |
            | 11:30-13:00 | 午餐+步行至孤山馆区 | 步行 约10分钟 |
            | 13:00-15:00 | 浙江省博物馆（孤山馆区） | - |
            | 15:20 | 入住酒店 | 公交 约10分钟 |

            ## 第2天（2026-10-26 周一）
            | 时段 | 安排 | 点间通勤 |
            |---|---|---|
            | 09:00-09:20 | 退房+地铁前往河坊街 | 地铁+步行 约20分钟 |
            | 09:20-11:30 | 河坊街+南宋御街 | - |
            | 11:30 | 返程 | - |

            调优过程：就近聚类分日（西湖文化簇 / 清河坊历史街区簇），通勤均 20 分钟内。
            """;

    private static final String SC2_POI_SHORTLIST = """
            # 杭州景点候选清单（poi_shortlist）

            | # | 景点 | 坐标（经度,纬度） | 门票 | 建议时长 | 开放时间 |
            |---|---|---|---|---|---|
            | 1 | 西湖（断桥—白堤—孤山） | 120.14912,30.25941 | 免费 | 2.5小时 | 全天开放 |
            | 2 | 浙江省博物馆（孤山馆区） | 120.14432,30.25476 | 免费 | 2小时 | 09:00-17:00（周一闭馆） |
            | 3 | 河坊街 | 120.17156,30.24472 | 免费 | 1.5小时 | 全天开放 |
            | 4 | 南宋御街 | 120.17356,30.24699 | 免费 | 1小时 | 全天开放 |
            """;

    /** 超支做进数据（酒店 700/晚 + 餐饮 220 + 交通 105 = 1025 > 预算 800，POI 全免费），重组装也修不掉 */
    private static final String SC2_ITINERARY_DRAFT = """
            # 杭州2日行程草案（2026-10-25 ~ 10-26，1人，预算800元）

            ## 第1天（10月25日 周日）
            | 时间 | 行程 | 地点 | 费用 | 点间通勤 |
            |---|---|---|---|---|
            | 09:00-11:30 | 西湖环湖（断桥→白堤→孤山） | 西湖 | 免费 | - |
            | 11:30-13:00 | 午餐+步行至孤山馆区 | 途中 | 70元 | 步行 约10分钟 |
            | 13:00-15:00 | 浙江省博物馆（孤山馆区） | 孤山 | 免费 | - |
            | 15:20 | 入住西子湖宾馆 | 南山路 | 700元/晚 | 公交 约10分钟 |

            ## 第2天（10月26日 周一）
            | 时间 | 行程 | 地点 | 费用 | 点间通勤 |
            |---|---|---|---|---|
            | 09:00-09:20 | 退房+地铁前往河坊街 | 途中 | - | 地铁+步行 约20分钟 |
            | 09:20-11:30 | 河坊街+南宋御街 | 河坊街 | 免费 | - |
            | 11:30-12:30 | 午餐后返程 | 河坊街 | 50元 | - |

            ## 费用汇总（1人）
            - 住宿: 700元（西子湖宾馆 舒适型）
            - 餐饮: 220元
            - 市内交通: 105元
            - 总计: 1025元 —— 超出预算 800 元达 225 元（超支 28%）

            > 天气提示：两日晴，15~24℃，适合户外游览。
            """;

    private static final String SC2_INTAKE = """
            # 需求收集结果（intake-agent 转交）

            - 目的地: 杭州（市内游）
            - 日期与天数: 2026-10-25 ~ 2026-10-26，共 2 天
            - 同行人: 1 人
            - 总预算: 800 元（硬约束，超支不可接受）
            - 住宿要求: 舒适型酒店
            - 偏好: 自然风光 + 人文历史
            - 大交通: 无城际大交通需求
            - 状态: CONFIRMED
            """;

    /** 质检结果回放桩（reviewer-agent）：不做审核判断，按送审次数回放预设裁决（首审/重审双文件二选一） */
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

    /** 场景1 首审裁决：段2 fail（跨城往返超时；格式复刻 ReviewerAgent 第七节契约） */
    private static final String SC1_VERDICT_FIRST = """
            # 质检报告：不通过

            - 总分: 62/100（通过线 80）
            - 失败段: 2（路线连贯性+时间密度：第1天 西湖→外滩→河坊街 单日跨城往返通勤合计约 365 分钟，远超单日上限）

            ## 分段结论

            | 段 | 审核项 | 结论 | 问题 |
            |---|---|---|---|
            | 1 | POI 有效性 | pass | 所有 POI 真实存在，营业时间与排期不冲突 |
            | 2 | 路线连贯性+时间密度 | fail | 第1天 杭州↔上海 两段跨城通勤 180+185=365 分钟，单日通勤严重超限 |
            | 3 | 预算+偏好匹配 | pass | 总计 681 元在预算 700 元内；双城与偏好覆盖完整 |

            | 维度 | 得分 | 主要扣分项 |
            |---|---|---|
            | 完备性 | 14 | - |
            | 可行性 | 12 | 跨城往返可行性弱 |
            | 时间冲突 | 10 | 第1天通勤超时 |
            | 费用预算 | 13 | - |
            | POI 合理性 | 13 | - |

            - 核验记录: getTransitRoute(西湖, 外滩) 实测约 210 分钟，超 90 分钟上限，与草案声明 180 分钟不符

            ## 改进建议（给 planning-agent 的回炉指令）
            按失败段组织，每条标注对应段号，具体到改哪一天、哪一项：
            1. [段2] 重排路线消除单日跨城往返：把外滩调整为独立半天行程，或按就近聚类重新分日，单日通勤合计控制在 90 分钟内

            REVIEW_RESULT: FAIL 总分=62 失败段=2
            """;

    /** 场景1 重审裁决：通过（格式复刻 ReviewerAgent 第六节契约） */
    private static final String SC1_VERDICT_RECHECK = """
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

    /** 场景2 首审裁决：段3 fail（预算超支硬算术 1025>800） */
    private static final String SC2_VERDICT_FIRST = """
            # 质检报告：不通过

            - 总分: 64/100（通过线 80）
            - 失败段: 3（预算+偏好匹配：总计 1025 元，超预算 800 元达 225 元，超支 28%，远超 ±10% 容忍线）

            ## 分段结论

            | 段 | 审核项 | 结论 | 问题 |
            |---|---|---|---|
            | 1 | POI 有效性 | pass | 所有 POI 真实存在，营业时间与排期不冲突（博物馆为周日场次，避开周一闭馆） |
            | 2 | 路线连贯性+时间密度 | pass | 西湖—孤山—河坊街小簇串联，各段通勤 10~20 分钟，时间衔接连贯 |
            | 3 | 预算+偏好匹配 | fail | 总计 1025 元 vs 预算 800 元，超支 225 元（28%）；住宿单晚 700 元占预算 87.5% |

            | 维度 | 得分 | 主要扣分项 |
            |---|---|---|
            | 完备性 | 15 | - |
            | 可行性 | 14 | - |
            | 时间冲突 | 14 | - |
            | 费用预算 | 8 | 超支 28%，住宿占比畸高 |
            | POI 合理性 | 13 | - |

            - 核验记录: 逐项复核费用汇总 700+220+105=1025 元，与草案声明一致

            ## 改进建议（给 planning-agent 的回炉指令）
            按失败段组织，每条标注对应段号，具体到改哪一天、哪一项：
            1. [段3] 换更经济酒店（舒适型控制在 350 元/晚内，如亚朵西湖店 320 元），总预算压回 800 元内

            REVIEW_RESULT: FAIL 总分=64 失败段=3
            """;

    /** 场景2 重审裁决：通过 */
    private static final String SC2_VERDICT_RECHECK = """
            # 质检通过

            - 总分: 86/100（通过线 80，单维最低线 12，三段全 pass）

            ## 分段结论

            | 段 | 审核项 | 结论 |
            |---|---|---|
            | 1 | POI 有效性 | pass |
            | 2 | 路线连贯性+时间密度 | pass |
            | 3 | 预算+偏好匹配 | pass |

            - 核验声明: 修订后总预算约 645 元，在 800 元 ±10% 内
            - 一句话总评: 酒店换档后预算达标，行程结构不变

            REVIEW_RESULT: PASS 总分=86
            """;

    private static final String TASK_BACKLOG = """
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

    private static final String SC1_HOTEL_CACHE = """
            汉庭酒店（杭州西湖店）｜经济型｜大床房 180元/晚｜上城区邮驿路，近西湖｜评分4.6""";

    private static final String SC2_HOTEL_CACHE = """
            西子湖宾馆｜舒适型｜大床房 700元/晚｜南山路，近西湖｜评分4.7""";

    // ==================== 测试 ====================

    @Test
    @DisplayName("场景1 段2 fail（跨城烂路线）→ 回炉只重跑 route-optimizer，cache_hit=poi，poi-research 不被 spawn")
    void segment2FailRoutesOnlyRouteOptimizer() throws Exception {
        Path workspaceRoot = Files.createTempDirectory("planner-rework-sc1-");
        try {
            RunOutcome r = runPlanner(workspaceRoot, "conv-9101",
                    Map.of(
                            "task_backlog.md", TASK_BACKLOG,
                            "intake_done.md", SC1_INTAKE,
                            "poi_shortlist.md", SC1_POI_SHORTLIST,
                            "route_plan.md", SC1_ROUTE_PLAN,
                            "itinerary_draft.md", SC1_ITINERARY_DRAFT,
                            "scripted_verdict_first.md", SC1_VERDICT_FIRST,
                            "scripted_verdict_recheck.md", SC1_VERDICT_RECHECK),
                    Map.of(
                            TaskType.WEATHER, WEATHER_CACHE,
                            TaskType.HOTEL, SC1_HOTEL_CACHE,
                            TaskType.POI, SC1_POI_SHORTLIST,
                            TaskType.ROUTE, SC1_ROUTE_PLAN));
            List<SpawnCall> spawns = r.spawns();
            long poiSpawns = count(spawns, PoiResearchAgent.AGENT_NAME);
            long routeSpawns = count(spawns, RouteOptimizerAgent.AGENT_NAME);
            long reviewerSpawns = count(spawns, ReviewerAgent.AGENT_NAME);

            assertEquals(0, poiSpawns,
                    "段2 fail 回炉不得重新 spawn poi-research（POI 池走缓存复用）。实际 spawn 序列: " + spawns);
            assertTrue(reviewerSpawns >= 2,
                    "应至少两次送审（首审 + 回炉重审）。实际 spawn 序列: " + spawns);
            long firstReviewAt = spawns.stream()
                    .filter(s -> s.agentId().equals(ReviewerAgent.AGENT_NAME))
                    .mapToLong(SpawnCall::at).min().orElseThrow();
            assertTrue(routeSpawns >= 1,
                    "段2 fail 回炉应重新 spawn route-optimizer 重排路线。实际 spawn 序列: " + spawns);
            assertTrue(spawns.stream()
                            .filter(s -> s.agentId().equals(RouteOptimizerAgent.AGENT_NAME))
                            .allMatch(s -> s.at() >= firstReviewAt),
                    "route-optimizer 只应在回炉阶段重跑（首轮 route 缓存命中即复用，不 spawn）。"
                            + "实际 spawn 序列: " + spawns);
            assertTrue(r.cacheHits().stream()
                            .anyMatch(h -> "poi".equals(h.type()) && h.at() >= firstReviewAt),
                    "FR-S14 锚点：回炉期应出现 cache_hit=poi 日志（POI 缓存复用）。"
                            + "实际 cache_hit 日志: " + r.cacheHits());
            // 软观测（非验收硬条件）：终态与回炉产物
            System.out.println("[PLANNER-TEST] 终态: review_passed.md 存在="
                    + Files.exists(r.taskDir().resolve("review_passed.md"))
                    + "，最终回复见上方打印");
        } finally {
            deleteRecursivelyBestEffort(workspaceRoot);
        }
    }

    @Test
    @DisplayName("场景2 段3 fail（预算超支）→ 不重委派任何子 Agent，planner 自行修订草案")
    void segment3FailPlannerSelfRevisesWithoutRespawn() throws Exception {
        Path workspaceRoot = Files.createTempDirectory("planner-rework-sc2-");
        try {
            RunOutcome r = runPlanner(workspaceRoot, "conv-9102",
                    Map.of(
                            "task_backlog.md", TASK_BACKLOG,
                            "intake_done.md", SC2_INTAKE,
                            "poi_shortlist.md", SC2_POI_SHORTLIST,
                            "route_plan.md", SC2_ROUTE_PLAN,
                            "itinerary_draft.md", SC2_ITINERARY_DRAFT,
                            "scripted_verdict_first.md", SC2_VERDICT_FIRST,
                            "scripted_verdict_recheck.md", SC2_VERDICT_RECHECK),
                    Map.of(
                            TaskType.WEATHER, WEATHER_CACHE,
                            TaskType.HOTEL, SC2_HOTEL_CACHE,
                            TaskType.POI, SC2_POI_SHORTLIST,
                            TaskType.ROUTE, SC2_ROUTE_PLAN));
            List<SpawnCall> spawns = r.spawns();
            long poiSpawns = count(spawns, PoiResearchAgent.AGENT_NAME);
            long reviewerSpawns = count(spawns, ReviewerAgent.AGENT_NAME);

            assertEquals(0, poiSpawns,
                    "段3 fail 回炉不得重新 spawn poi-research。实际 spawn 序列: " + spawns);
            assertTrue(reviewerSpawns >= 2,
                    "应至少两次送审（首审 + 自行修订后重审）。实际 spawn 序列: " + spawns);
            // 段3 的验收契约落在首次回炉窗口：reviewer#1 → reviewer#2 之间必须是
            // planner 纯自行修订（零委派）；reviewer#2 之后若按新失败段路由重跑子 Agent，
            // 属多轮回炉的合法路由（如二次评审判段2 → 重跑 route-optimizer）
            List<Long> reviewTimes = spawns.stream()
                    .filter(s -> s.agentId().equals(ReviewerAgent.AGENT_NAME))
                    .mapToLong(SpawnCall::at).sorted().boxed().toList();
            long secondReviewAt = reviewTimes.get(1);
            assertTrue(spawns.stream()
                            .filter(s -> !s.agentId().equals(ReviewerAgent.AGENT_NAME))
                            .allMatch(s -> s.at() >= secondReviewAt),
                    "段3 fail 的首次回炉必须由 planner 自行重组装（reviewer#1 → reviewer#2 "
                            + "之间不得 spawn poi-research / route-optimizer）。实际 spawn 序列: " + spawns);
            String draftAfter = Files.readString(r.taskDir().resolve("itinerary_draft.md"),
                    StandardCharsets.UTF_8);
            System.out.println("[PLANNER-TEST] 修订后草案:\n" + draftAfter);
            assertNotEquals(SC2_ITINERARY_DRAFT, draftAfter,
                    "planner 应自行修订 itinerary_draft.md（换更经济酒店/调整行程）");
            // 软观测（非验收硬条件）：终态
            System.out.println("[PLANNER-TEST] 终态: review_passed.md 存在="
                    + Files.exists(r.taskDir().resolve("review_passed.md"))
                    + "，最终回复见上方打印");
        } finally {
            deleteRecursivelyBestEffort(workspaceRoot);
        }
    }

    // ==================== 场景执行 ====================

    /** 一次 planner 全链运行的可观测结果（spawn 时间线 / cache_hit 日志 / 最终回复 / 产物目录） */
    record RunOutcome(List<SpawnCall> spawns, List<CacheHitLog> cacheHits, String finalReply,
                      List<String> toolTimeline, Path taskDir) {
    }

    record SpawnCall(String agentId, long at) {
        @Override
        public String toString() {
            return agentId + "@" + at;
        }
    }

    record CacheHitLog(String type, long at) {
        @Override
        public String toString() {
            return "cache_hit=" + type + "@" + at;
        }
    }

    private RunOutcome runPlanner(Path workspaceRoot, String sessionId,
                                  Map<String, String> collabFiles,
                                  Map<TaskType, String> cacheSeeds) throws Exception {
        // ---- 协作目录预置（文件工具把相对路径解析到 {workspace}/{userId}/ 之下）----
        Path taskDir = workspaceRoot.resolve(USER_ID).resolve("tasks").resolve(sessionId);
        Files.createDirectories(taskDir);
        for (Map.Entry<String, String> e : collabFiles.entrySet()) {
            Files.writeString(taskDir.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
        }

        // ---- 缓存预登记（ThrowingRedisTemplate 强制内存降级，不碰真实 Redis，保证可重复）----
        TaskResultCache cache = new TaskResultCache(new ThrowingRedisTemplate());
        cacheSeeds.forEach((type, content) -> cache.register(USER_ID, sessionId, type, "-", content));

        // ---- 工具面：真实缓存工具 + 确定性桩（外部 API 免配额抖动，B1 同款思路）----
        RagServiceImpl rag = Mockito.mock(RagServiceImpl.class);
        Mockito.when(rag.isAvailable()).thenReturn(false);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new PoiRagTools(rag, cache));
        toolkit.registerTool(new PlannerStubs());

        // ---- 观测通道：spawn 记录中间件 + TaskResultCache 日志捕获 ----
        SpawnRecorder recorder = new SpawnRecorder();
        RecordingAppender appender = new RecordingAppender();
        appender.start();
        org.slf4j.Logger rawLogger = LoggerFactory.getLogger(TaskResultCache.class);
        ch.qos.logback.classic.Logger cacheLogger =
                rawLogger instanceof ch.qos.logback.classic.Logger lb ? lb : null;
        if (cacheLogger != null) {
            cacheLogger.addAppender(appender);
        }

        try {
            HarnessAgent planner = buildPlanner(workspaceRoot, toolkit, recorder);
            RuntimeContext ctx = RuntimeContext.builder()
                    .userId(USER_ID).sessionId(sessionId).build();
            // 生产同款注入（ChatService）：子代理经此键解析主会话 ID
            ctx.put(IntentRouterMiddleware.CTX_COLLAB_DIR_KEY, "tasks/" + sessionId);
            // 测试确定性（框架原生机制）：强制 spawn 同步等待且超时不转后台，
            // 隔离「同步等待 60s 超时→转后台→通知回流」路径对回炉闭环的干扰；
            // 生产环境无此键，依赖提示词的【同步委派】纪律（ItineraryAgent 执行规范）
            ctx.put(AgentSpawnTool.CTX_FORCE_SYNC, Boolean.TRUE);
            ctx.put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 300);

            String task = "协作目录: tasks/" + sessionId + "（会话 ID: " + sessionId + "）\n"
                    + "进度：任务 T1~T4 已完成——poi_shortlist.md、route_plan.md 与"
                    + " itinerary_draft.md（初版）均已产出，天气/酒店/POI/路线四类缓存"
                    + "均已登记（get_cached_task_result 可查）。\n"
                    + "itinerary_draft.md 初版已组装完成，保持原样（送审前不要自行修订），"
                    + "请直接 spawn reviewer-agent 送审（第 4 步），并按质检结论完成回炉闭环。";

            StringBuilder replyBuf = new StringBuilder();
            List<String> toolTimeline = new CopyOnWriteArrayList<>();
            planner.streamEvents(new UserMessage(task), ctx)
                    .doOnNext(event -> {
                        if (event instanceof ToolResultEndEvent e) {
                            toolTimeline.add(e.getToolCallName() + "@" + System.currentTimeMillis());
                            // 与生产口径一致：spawn 结果是子 Agent 最终消息，
                            // 工具轮结束即重置缓冲，只保留最后一段文本（planner 最终回复）
                            replyBuf.setLength(0);
                        } else if (event instanceof TextBlockDeltaEvent e) {
                            replyBuf.append(e.getDelta());
                        }
                    })
                    .blockLast(RUN_TIMEOUT);

            List<CacheHitLog> hits = appender.events.stream()
                    .filter(e -> e.getFormattedMessage() != null
                            && e.getFormattedMessage().startsWith("cache_hit="))
                    .map(e -> new CacheHitLog(
                            e.getFormattedMessage().substring("cache_hit=".length()).split(" ")[0].trim(),
                            e.getTimeStamp()))
                    .toList();
            System.out.println("[PLANNER-TEST] spawn 时间线: " + recorder.spawns);
            System.out.println("[PLANNER-TEST] 工具结果时间线: " + toolTimeline);
            System.out.println("[PLANNER-TEST] cache_hit 日志: " + hits);
            System.out.println("[PLANNER-TEST] planner 最终回复:\n" + replyBuf);
            printFileIfExists(taskDir, "review_report.md");
            printFileIfExists(taskDir, "review_passed.md");
            return new RunOutcome(List.copyOf(recorder.spawns), hits,
                    replyBuf.toString().strip(), List.copyOf(toolTimeline), taskDir);
        } finally {
            if (cacheLogger != null) {
                cacheLogger.detachAppender(appender);
            }
            appender.stop();
        }
    }

    /** 复刻 AgentConfig.buildPlannerAgent 的 4-Agent 图（planner + 三声明式子 Agent） */
    private static HarnessAgent buildPlanner(Path workspaceRoot, Toolkit toolkit,
                                             SpawnRecorder recorder) throws IOException {
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

        // 质检子 Agent：脚本化裁决回放桩（见类 javadoc——裁决的生产已由 B1 真实验收，
        // 本测试聚焦 planner 按段路由，裁决固定为受控输入以保证可重复）
        SubagentDeclaration reviewer = SubagentDeclaration.builder()
                .name(ReviewerAgent.AGENT_NAME)
                .description("质量审阅 Agent（质检员）：对 itinerary_draft.md 做分段审核"
                        + "（段1 POI 有效性 / 段2 路线连贯性+时间密度 / 段3 预算+偏好匹配，各段 pass|fail），"
                        + "通过写 review_passed.md，不通过写 review_report.md"
                        + "（含分段结论与改进建议，供 planner 按段回炉）")
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

    private static long count(List<SpawnCall> spawns, String agentId) {
        return spawns.stream().filter(s -> s.agentId().equals(agentId)).count();
    }

    private static void printFileIfExists(Path taskDir, String fileName) throws IOException {
        Path file = taskDir.resolve(fileName);
        if (Files.exists(file)) {
            System.out.println("[PLANNER-TEST] " + fileName + ":\n"
                    + Files.readString(file, StandardCharsets.UTF_8));
        }
    }

    // ==================== 观测组件 ====================

    /**
     * spawn 记录中间件：仿 ReviewerRetryMiddleware 的 onActing 拦截模式，
     * 透传记录 planner 的每次 agent_spawn（agent_id + 时间戳），与 cache_hit
     * 日志时间戳对齐以区分「首轮」与「回炉」阶段。order=-800：观察点在
     * SubagentsMiddleware（默认 1）之前即可。
     */
    static final class SpawnRecorder implements MiddlewareBase {

        final List<SpawnCall> spawns = new CopyOnWriteArrayList<>();

        @Override
        public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                         java.util.function.Function<ActingInput, Flux<AgentEvent>> next) {
            for (ToolUseBlock call : input.toolCalls()) {
                if ("agent_spawn".equals(call.getName())) {
                    Object agentId = call.getInput() != null ? call.getInput().get("agent_id") : null;
                    spawns.add(new SpawnCall(agentId != null ? String.valueOf(agentId) : "?",
                            System.currentTimeMillis()));
                }
            }
            return next.apply(input);
        }

        @Override
        public int order() {
            return -800;
        }

        // ==================== 其余阶段透传 ====================

        @Override
        public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                        java.util.function.Function<AgentInput, Flux<AgentEvent>> next) {
            return next.apply(input);
        }

        @Override
        public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                            java.util.function.Function<ReasoningInput, Flux<AgentEvent>> next) {
            return next.apply(input);
        }

        @Override
        public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                             java.util.function.Function<ModelCallInput, Flux<AgentEvent>> next) {
            return next.apply(input);
        }
    }

    /** 线程安全的 logback 记录 appender（reactive 线程并发写日志，ListAppender 非线程安全） */
    static final class RecordingAppender extends AppenderBase<ILoggingEvent> {
        final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            events.add(event);
        }
    }

    /** opsForValue 全抛异常 → TaskResultCache 全程内存降级（TaskResultCacheTest 同款，不碰真实 Redis） */
    static final class ThrowingRedisTemplate extends org.springframework.data.redis.core.StringRedisTemplate {
        @Override
        public org.springframework.data.redis.core.ValueOperations<String, String> opsForValue() {
            throw new IllegalStateException("测试环境无 Redis，强制走内存降级");
        }
    }

    // ==================== 确定性桩工具 ====================

    /**
     * 外部 API 确定性桩（与生产工具同名同语义，B1 VerificationStubs 思路）：
     * geocode 返回真实感坐标；路线工具按坐标距离返回时长（跨城 ~3.5h / 市内 ~25km/h
     * 门到门），使「跨城往返」可被 reviewer 用工具核验为超时、市内路线核验为顺畅；
     * searchHotels 提供高中低价位档（段3 回炉的「换酒店」素材）。
     */
    @SuppressWarnings("unused")
    static final class PlannerStubs {

        private static final double[] WEST_LAKE = {120.14912, 30.25941};
        private static final double[] LINGYIN = {120.09961, 30.24088};
        private static final double[] XIXI = {120.06280, 30.26920};
        private static final double[] HEFANG = {120.17156, 30.24472};
        private static final double[] BUND = {121.49032, 31.23490};

        @Tool(description = "地址 → 坐标（返回 JSON：formatted_address/location）")
        public String geocode(String address) {
            double[] loc = locate(address);
            return "{\"status\":\"1\",\"geocodes\":[{\"formatted_address\":\"浙江省"
                    + (address != null && address.contains("外滩") ? "上海市" : "杭州市") + address
                    + "\",\"location\":\"" + String.format(java.util.Locale.ROOT, "%.6f,%.6f",
                    loc[0], loc[1]) + "\"}]}";
        }

        @Tool(description = "公共交通路线核验/规划：返回 duration（秒）/ distance（米）")
        public String getTransitRoute(String origin, String destination, String city) {
            return routeJson(origin, destination);
        }

        @Tool(description = "驾车路线核验/规划：返回 duration（秒）/ distance（米）")
        public String getDrivingRoute(String origin, String destination) {
            return routeJson(origin, destination);
        }

        /** 跨城（>50km）≈ 3.5 小时（高铁+接驳）；市内按 ~7km/h 门到门（含步行/候车），至少 10 分钟 */
        private static String routeJson(String origin, String destination) {
            double[] o = parseLngLat(origin);
            double[] d = parseLngLat(destination);
            if (o == null || d == null) {
                return "{\"status\":\"1\",\"route\":{\"duration\":1200,\"distance\":5000}}";
            }
            double meters = haversineMeters(o[0], o[1], d[0], d[1]);
            long seconds = meters > 50_000
                    ? 12_600
                    : Math.max(600, Math.round(meters / 2.0));
            return "{\"status\":\"1\",\"route\":{\"duration\":" + seconds
                    + ",\"distance\":" + Math.round(meters) + "}}";
        }

        @Tool(description = "查询指定城市实时天气（温度/天气状况）")
        public String getWeather(String city) {
            return "{\"now\":{\"text\":\"晴\",\"temp\":\"20\"}}";
        }

        @Tool(description = "查询城市未来 N 天天气预报")
        public String getWeatherForecast(String city, int days) {
            return "杭州未来" + days + "天：10-25 晴 15~24℃；10-26 晴转多云 16~23℃";
        }

        @Tool(description = "按城市关键词搜索酒店（返回名称/价位/类型/地址）")
        public String searchHotels(String city, String keyword, int pageSize) {
            return "{\"status\":\"1\",\"hotels\":["
                    + "{\"name\":\"西子湖宾馆\",\"price\":700,\"type\":\"舒适型\",\"address\":\"南山路\",\"rating\":\"4.7\"},"
                    + "{\"name\":\"亚朵酒店（西湖店）\",\"price\":320,\"type\":\"舒适型\",\"address\":\"上城区国货路\",\"rating\":\"4.6\"},"
                    + "{\"name\":\"汉庭酒店（河坊街店）\",\"price\":160,\"type\":\"经济型\",\"address\":\"上城区河坊街\",\"rating\":\"4.5\"}]}";
        }

        @Tool(description = "按城市关键词搜索 POI（返回名称/坐标/门票/开放时间）")
        public String searchPois(String city, String keyword, int pageSize) {
            // 查询感知：西湖周边知名景点词直接命中（全部真实免费/低价，坐标与 geocode 桩一致），
            // 避免模型换词重查时拿到重复结果陷入搜索循环
            String[][] known = {
                    {"苏堤", "苏堤春晓", "120.14860,30.24053", "免费", "全天开放"},
                    {"花港观鱼", "花港观鱼", "120.14682,30.23520", "免费", "08:00-17:00"},
                    {"曲院风荷", "曲院风荷", "120.13966,30.25703", "免费", "全天开放"},
                    {"柳浪闻莺", "柳浪闻莺", "120.15637,30.23903", "免费", "全天开放"},
                    {"太子湾", "太子湾公园", "120.14125,30.22950", "免费", "全天开放"},
                    {"雷峰塔", "雷峰塔", "120.14885,30.23200", "40元", "08:00-19:00"},
                    {"博物馆", "浙江省博物馆（孤山馆区）", "120.14432,30.25476", "免费", "09:00-17:00（周一闭馆）"},
                    {"河坊", "河坊街", "120.17156,30.24472", "免费", "全天开放"},
                    {"南宋", "南宋御街", "120.17356,30.24699", "免费", "全天开放"},
                    {"灵隐", "灵隐寺", "120.09961,30.24088", "45元", "07:00-18:00"},
                    {"西溪", "西溪国家湿地公园", "120.06280,30.26920", "80元", "08:00-17:30"},
                    {"西湖", "西湖（断桥—白堤—苏堤）", "120.14912,30.25941", "免费", "全天开放"},
            };
            if (keyword != null) {
                for (String[] k : known) {
                    if (keyword.contains(k[0])) {
                        return "{\"status\":\"1\",\"pois\":[{\"name\":\"" + k[1]
                                + "\",\"location\":\"" + k[2] + "\",\"ticket\":\"" + k[3]
                                + "\",\"open_time\":\"" + k[4] + "\"}]}";
                    }
                }
            }
            return "{\"status\":\"1\",\"pois\":["
                    + "{\"name\":\"西湖（断桥—白堤—苏堤）\",\"location\":\"120.14912,30.25941\",\"ticket\":\"免费\",\"open_time\":\"全天开放\"},"
                    + "{\"name\":\"河坊街\",\"location\":\"120.17156,30.24472\",\"ticket\":\"免费\",\"open_time\":\"全天开放\"},"
                    + "{\"name\":\"苏堤春晓\",\"location\":\"120.14860,30.24053\",\"ticket\":\"免费\",\"open_time\":\"全天开放\"},"
                    + "{\"name\":\"柳浪闻莺\",\"location\":\"120.15637,30.23903\",\"ticket\":\"免费\",\"open_time\":\"全天开放\"}]}";
        }

        @Tool(description = "更新指定任务的执行状态。planning-agent 每完成一项任务必须调用本工具回报")
        public String update_task_status(
                @ToolParam(name = "sessionId", description = "本轮会话 ID，与任务清单登记时一致")
                String sessionId,
                @ToolParam(name = "taskId", description = "任务 ID，如 T1")
                String taskId,
                @ToolParam(name = "status", description = "新状态：IN_PROGRESS 开始执行 / DONE 成功 / FAILED 失败")
                String status) {
            return "OK: 任务 " + taskId + " 状态已更新为 " + status;
        }

        /** 地址关键词 → 真实感坐标（覆盖 fixture 中的全部 POI/场馆） */
        private static double[] locate(String address) {
            if (address == null) {
                return WEST_LAKE;
            }
            if (address.contains("外滩")) {
                return BUND;
            }
            if (address.contains("虹桥")) {
                return new double[]{121.32058, 31.19463};
            }
            if (address.contains("杭州东")) {
                return new double[]{120.21291, 30.29075};
            }
            if (address.contains("灵隐")) {
                return LINGYIN;
            }
            if (address.contains("西溪")) {
                return XIXI;
            }
            if (address.contains("博物馆") || address.contains("孤山")) {
                return new double[]{120.14432, 30.25476};
            }
            if (address.contains("花港观鱼")) {
                return new double[]{120.14682, 30.23520};
            }
            if (address.contains("曲院风荷")) {
                return new double[]{120.13966, 30.25703};
            }
            if (address.contains("柳浪闻莺")) {
                return new double[]{120.15637, 30.23903};
            }
            if (address.contains("太子湾")) {
                return new double[]{120.14125, 30.22950};
            }
            if (address.contains("雷峰塔")) {
                return new double[]{120.14885, 30.23200};
            }
            if (address.contains("河坊") || address.contains("御街")) {
                return HEFANG;
            }
            if (address.contains("汉庭") || address.contains("亚朵")) {
                return new double[]{120.18300, 30.24100};
            }
            if (address.contains("西子湖")) {
                return new double[]{120.15032, 30.24637};
            }
            if (address.contains("西湖") || address.contains("断桥") || address.contains("白堤")
                    || address.contains("苏堤")) {
                return WEST_LAKE;
            }
            long h = Math.abs(address.hashCode());
            return new double[]{120.10 + (h % 100) / 1000.0, 30.20 + (h / 100 % 100) / 1000.0};
        }

        private static double[] parseLngLat(String s) {
            if (s == null) {
                return null;
            }
            // 兼容 "经度,纬度" 与 geocode 返回的 location 字段
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(1[0-2]\\d\\.\\d+)\\s*,\\s*(3\\d\\.\\d+)")
                    .matcher(s);
            if (m.find()) {
                return new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))};
            }
            // 不是坐标则按地址关键词定位
            return locate(s);
        }

        private static double haversineMeters(double lng1, double lat1, double lng2, double lat2) {
            double radLat1 = Math.toRadians(lat1);
            double radLat2 = Math.toRadians(lat2);
            double dLat = Math.toRadians(lat2 - lat1);
            double dLng = Math.toRadians(lng2 - lng1);
            double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                    + Math.cos(radLat1) * Math.cos(radLat2)
                    * Math.sin(dLng / 2) * Math.sin(dLng / 2);
            return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        }
    }

    /** 尽力清理（harness 工作区句柄未释放时残留无害，B1 同款） */
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
