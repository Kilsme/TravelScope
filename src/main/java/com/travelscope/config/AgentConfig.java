package com.travelscope.config;

import com.travelscope.agent.IntentCascadeRouter;
import com.travelscope.agent.IntentClassifier;
import com.travelscope.agent.IntentRouterMiddleware;
import com.travelscope.agent.IntakeAgent;
import com.travelscope.agent.ItineraryAgent;
import com.travelscope.agent.LightweightIntentClassifier;
import com.travelscope.agent.PlanningGateMiddleware;
import com.travelscope.agent.PoiResearchAgent;
import com.travelscope.agent.ReviewerAgent;
import com.travelscope.agent.ReviewerRetryMiddleware;
import com.travelscope.agent.RouteOptimizerAgent;
import com.travelscope.agent.TravelMasterAgent;
import com.travelscope.agent.tools.AttractionTool;
import com.travelscope.agent.tools.HotelTool;
import com.travelscope.agent.tools.RequirementTools;
import com.travelscope.agent.tools.TaskTools;
import com.travelscope.agent.tools.TransportTool;
import com.travelscope.agent.tools.WeatherTool;
import com.travelscope.service.IntentCache;
import com.travelscope.service.TaskRegistry;
import com.travelscope.service.TripRequirementStore;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AgentScope 多智能体配置类
 * <p>
 * 组装架构（v3，6 Agent 编排）：
 * <pre>
 * 主 Agent（travel-master，HarnessAgent）
 *  ├── 工具：WeatherTool / HotelTool / AttractionTool / TransportTool
 *  │         + TaskTools / RequirementTools + MCP（12306 火车票 / 飞常准机票）
 *  ├── Middleware：IntentRouterMiddleware → PlanningGateMiddleware → ReviewerRetryMiddleware
 *  ├── subagent: intake-agent（需求收集，声明式叶子）
 *  └── subagentFactory: planning-agent（二级编排者，手工构建非叶子）
 *        ├── subagent: poi-research（景点检索筛选）
 *        ├── subagent: route-optimizer（分日路线调优）
 *        └── subagent: reviewer-agent（5 维质检）
 * </pre>
 * </p>
 * <p>
 * planning-agent 经 subagentFactory 工厂手工构建的原因：声明式 SubagentDeclaration
 * 注册的子 Agent 会被框架标记为叶子节点（toolkit 无 agent_spawn，无法再委派），
 * 而 v3 要求 Planner 并行调度 poi-research / route-optimizer 并送审 reviewer
 * （需求文档 4.1/4.2），故走公开 API subagentFactory(name, description, factory)
 * 在工厂内手工构建——工厂返回的 Agent 不做叶子标记，build 时自带 SubagentsMiddleware。
 * spawn 深度 master(0) → planner(1) → poi/route/reviewer(2) ≤ 框架上限 3，合法。
 * </p>
 * <p>
 * 协作流程（需求文档 4.2）：
 * <ol>
 *   <li>主 Agent 意图路由（应用层）</li>
 *   <li>委派 intake-agent 收口需求（状态机工具判缺项，≤3 轮反问）→ intake_done.md</li>
 *   <li>主 Agent 拆分任务，create_task_backlog 登记任务容器</li>
 *   <li>委派 planning-agent：直调工具 + 并行 spawn poi/route + 组装 + reviewer 质检</li>
 *   <li>主 Agent 读取 itinerary_draft.md / review_passed.md 整合返回用户</li>
 * </ol>
 * </p>
 */
@Configuration
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    private final AppProperties appProperties;
    private final WeatherTool weatherTool;
    private final HotelTool hotelTool;
    private final AttractionTool attractionTool;
    private final TransportTool transportTool;

    public AgentConfig(AppProperties appProperties,
                       WeatherTool weatherTool,
                       HotelTool hotelTool,
                       AttractionTool attractionTool,
                       TransportTool transportTool) {
        this.appProperties = appProperties;
        this.weatherTool = weatherTool;
        this.hotelTool = hotelTool;
        this.attractionTool = attractionTool;
        this.transportTool = transportTool;
    }

    /**
     * 创建 Toolkit（工具集）
     * <p>
     * 注册天气查询、酒店搜索、景点搜索、市内交通等本地工具，
     * 任务容器工具（TaskTools）与需求状态机工具（RequirementTools），
     * 并通过 MCP 客户端接入火车票（12306）与飞机票（飞常准）实时查询工具，
     * 供主 Agent 和各子 Agent（SHARED 工作区、共享 Toolkit）共同使用。
     * </p>
     */
    @Bean
    public Toolkit travelToolkit(TaskRegistry taskRegistry, TripRequirementStore tripRequirementStore) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(weatherTool);
        toolkit.registerTool(hotelTool);
        toolkit.registerTool(attractionTool);
        toolkit.registerTool(transportTool);
        // 任务容器工具：create_task_backlog / get_task_progress / update_task_status
        // 主 Agent 与规划子 Agent（SHARED 工作区、共享 Toolkit）均可调用
        toolkit.registerTool(new TaskTools(taskRegistry));
        // 需求状态机工具（v3 FR-S02）：intake-agent 判缺项/写回用，判断环节零模型调用
        toolkit.registerTool(new RequirementTools(tripRequirementStore));
        registerMcpClients(toolkit);
        log.info("Toolkit 注册完成: 天气/酒店/景点/交通 + 任务容器 + 需求状态机 + MCP(12306、飞常准)");
        return toolkit;
    }

    /**
     * 行程需求状态仓库 Bean（intake-agent 的状态机存储，userId:sessionId 双键隔离）
     */
    @Bean
    public TripRequirementStore tripRequirementStore() {
        return new TripRequirementStore();
    }

    /**
     * 任务容器注册表（用户+会话双级隔离，见 TaskRegistry）
     */
    @Bean
    public TaskRegistry taskRegistry(TaskWorkspaceService taskWorkspaceService) {
        return new TaskRegistry(taskWorkspaceService);
    }

    /**
     * 注册 MCP 客户端（stdio 模式拉起 npx 子进程）
     * <p>
     * - 12306（{@code npx -y 12306-mcp}）：实时火车票余票查询，无需鉴权
     * - 飞常准（{@code npx -y @variflight-ai/variflight-mcp}）：实时机票/航班查询，
     *   API Key 从环境变量 {@code VARIFLIGHT_API_KEY} 读取
     * </p>
     * 注册失败不阻断应用启动，仅告警并跳过对应工具。
     */
    private void registerMcpClients(Toolkit toolkit) {
        try {
            McpClientWrapper c12306 = McpClientBuilder.create("c12306")
                    .stdioTransport(npxCommand(), npxArgs("-y", "12306-mcp"), Map.of())
                    .buildSync();
            toolkit.registerMcpClient(c12306).block(Duration.ofSeconds(60));
            log.info("MCP 客户端注册成功: 12306-mcp (火车票实时查询)");
        } catch (Exception e) {
            log.warn("12306 MCP 客户端注册失败，火车票实时查询工具不可用: {}", e.getMessage());
        }

        String variflightKey = System.getenv("VARIFLIGHT_API_KEY");
        if (variflightKey == null || variflightKey.isBlank()) {
            log.warn("环境变量 VARIFLIGHT_API_KEY 未配置，跳过飞常准 MCP 注册，机票实时查询工具不可用");
            return;
        }
        try {
            McpClientWrapper variflight = McpClientBuilder.create("variflight")
                    .stdioTransport(npxCommand(), npxArgs("-y", "@variflight-ai/variflight-mcp"),
                            Map.of("VARIFLIGHT_API_KEY", variflightKey))
                    .buildSync();
            toolkit.registerMcpClient(variflight).block(Duration.ofSeconds(60));
            log.info("MCP 客户端注册成功: variflight (机票实时查询)");
        } catch (Exception e) {
            log.warn("飞常准 MCP 客户端注册失败，机票实时查询工具不可用: {}", e.getMessage());
        }
    }

    /**
     * Windows 下 ProcessBuilder 无法直接启动 npx（批处理脚本），
     * 统一经 {@code cmd /c npx ...} 拉起；非 Windows 直接用 npx。
     */
    private String npxCommand() {
        return isWindows() ? "cmd" : "npx";
    }

    private List<String> npxArgs(String... npxArgs) {
        List<String> args = new java.util.ArrayList<>();
        if (isWindows()) {
            args.add("/c");
            args.add("npx");
        }
        args.addAll(List.of(npxArgs));
        return args;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 创建 DashScope 聊天模型 Bean（主链路 qwen-plus）
     * <p>
     * 显式构建（apiKey 取自 travelscope.dashscope.api-key，即环境变量 API_KEY），
     * 供主 Agent 与意图分类器共用；子代理的字符串模型 ID 通过 modelResolver 解析到同一实例，
     * 避免依赖 DASHSCOPE_API_KEY 环境变量的字符串模型自动解析路径。
     * </p>
     * <p>
     * @Primary：容器内现在有两个 DashScopeChatModel Bean（本 Bean + turbo），
     * 按类型注入的 ChatService 等回退到本主链路模型。
     * </p>
     */
    @Bean
    @org.springframework.context.annotation.Primary
    public DashScopeChatModel dashscopeChatModel() {        return DashScopeChatModel.builder()
                .apiKey(appProperties.getDashscope().getApiKey())
                .modelName(appProperties.getDashscope().getModel())
                .stream(true)
                .build();
    }

    /**
     * L2 轻量分类模型 Bean（qwen-turbo，FR-S01 意图级联）
     * <p>
     * 与主链路共用 apiKey，仅模型名不同（travelscope.intent-cascade.l2-model）；
     * 仅被意图级联的 L2 层使用（LightweightIntentClassifier）。
     * </p>
     */
    @Bean
    public DashScopeChatModel dashscopeTurboModel() {
        return DashScopeChatModel.builder()
                .apiKey(appProperties.getDashscope().getApiKey())
                .modelName(appProperties.getIntentCascade().getL2Model())
                .stream(true)
                .build();
    }

    /**
     * 创建主 Agent（TravelMasterAgent）
     * <p>
     * 主 Agent 负责：
     * - 意图路由（应用层分类 + 路由指令注入）
     * - 委派 intake-agent 收口需求（缺项反问）
     * - 任务拆分并登记任务容器
     * - 委派 planning-agent（二级编排者）执行完整规划
     * - 读取执行结果，整合返回用户
     * </p>
     */
    @Bean
    public HarnessAgent travelMasterAgent(Toolkit toolkit, DashScopeChatModel dashscopeChatModel,
                                          TaskRegistry taskRegistry) throws IOException {
        // 需求收集子 Agent（intake-agent）：inline 模式声明，注册到主 Agent
        // tools 白名单限定其只能调需求状态机工具（get_missing_fields / update_requirement_state）
        SubagentDeclaration intakeSubAgent = SubagentDeclaration.builder()
                .name(IntakeAgent.AGENT_NAME)
                .description("需求收集 Agent（接待员），负责规划前的关键信息反问："
                        + "调需求状态机工具判断缺项（纯代码零模型调用），理解用户模糊回答并写回状态，"
                        + "≤3 轮反问后收齐或带默认值放行，产出 intake_done.md")
                .inlineAgentsBody(IntakeAgent.SYS_PROMPT)
                .model(appProperties.getDashscope().getModel())
                .maxIters(IntakeAgent.MAX_ITERS)
                .workspaceMode(WorkspaceMode.SHARED)
                .tools(List.of(
                        "get_missing_fields",
                        "update_requirement_state"))
                .build();

        HarnessAgent agent = HarnessAgent.builder()
                .name(TravelMasterAgent.AGENT_NAME)
                .sysPrompt(TravelMasterAgent.SYS_PROMPT)
                .model(dashscopeChatModel)
                // 子代理声明的字符串模型 ID 统一解析到同一模型实例
                .modelResolver(name -> dashscopeChatModel)
                .toolkit(toolkit)
                .maxIters(TravelMasterAgent.MAX_ITERS)
                // 责任链：意图路由（onSystemPrompt 注入本轮路由指令）
                //         + 规划委派门禁（onActing 拦截 agent_spawn，强制先登记任务清单）
                //         + Reviewer 回炉（v3 骨架空壳透传，拦截逻辑待实现）
                .middlewares(List.of(new IntentRouterMiddleware(),
                        new PlanningGateMiddleware(taskRegistry),
                        new ReviewerRetryMiddleware()))
                // 本助手全部为只读查询工具 + 工作区 MD 文件协作，BYPASS 免确认，
                // 否则工具调用会挂起等待用户确认导致对话提前结束
                .permissionContext(PermissionContextState.builder()
                        .mode(PermissionMode.BYPASS)
                        .build())
                .workspace(appProperties.getAgentscope().getWorkspacePath())
                .skillRepository(new ClasspathSkillRepository("skills"))
                .stateStore(new InMemoryAgentStateStore())
                // 子 Agent 一：intake-agent（声明式叶子，需求收集）
                .subagent(intakeSubAgent)
                // 子 Agent 二：planning-agent（subagentFactory 工厂手工构建非叶子二级编排者，
                // 工厂内挂 poi-research / route-optimizer / reviewer 三个声明，见 buildPlannerAgent；
                // 框架每次 spawn 时调用工厂 build 新实例，与 IntentClassifier 每次新建同款模式）
                .subagentFactory(ItineraryAgent.AGENT_NAME,
                        "规划 Agent（行程规划师，二级编排者）：直调工具获取天气/酒店/车票实时数据，"
                                + "并行调度 poi-research 与 route-optimizer 两个子 Agent，"
                                + "组装行程草案并经 reviewer-agent 质检闭环",
                        name -> buildPlannerAgent(toolkit, dashscopeChatModel))
                .build();

        log.info("主 Agent 构建完成: {} (workspace={}, 子 Agent: {} 声明式 + {} 工厂式[内含 {}/{}/{}])",
                TravelMasterAgent.AGENT_NAME,
                appProperties.getAgentscope().getWorkspacePath(),
                IntakeAgent.AGENT_NAME,
                ItineraryAgent.AGENT_NAME,
                PoiResearchAgent.AGENT_NAME, RouteOptimizerAgent.AGENT_NAME, ReviewerAgent.AGENT_NAME);
        return agent;
    }

    /**
     * 手工构建规划 Agent（planning-agent，非叶子二级编排者）
     * <p>
     * 不走声明式 SubagentDeclaration（其子 Agent 被框架标记为叶子、无 agent_spawn），
     * 而是直接用 HarnessAgent.builder() 构建：build 时自动安装 SubagentsMiddleware，
     * 注册 agent_spawn / agent_send / agent_list / task_* 工具，因此本 Agent 能 spawn
     * 自己声明的三个子 Agent。深度 master(0) → planner(1) → 子(2)，在框架上限 3 之内。
     * </p>
     * <p>
     * 传入共享 toolkit 引用（HarnessAgent.build() 内部会 copy，浅拷贝共享工具实例，
     * 不污染 travelToolkit Bean，MCP npx 进程不会重复拉起）；
     * workspace / skillRepository / 权限模式与主 Agent 一致。
     * </p>
     */
    private HarnessAgent buildPlannerAgent(Toolkit toolkit, DashScopeChatModel dashscopeChatModel) {
        // 景点检索子 Agent：多轮换词重查（RAG 双路检索待 FR-S11 落地后接入）
        SubagentDeclaration poiResearch = SubagentDeclaration.builder()
                .name(PoiResearchAgent.AGENT_NAME)
                .description("景点检索 Agent（检索员）：按目的地+天数+偏好多轮检索筛选景点候选，"
                        + "召回不足时换关键词重查，产出 poi_shortlist.md")
                .inlineAgentsBody(PoiResearchAgent.SYS_PROMPT)
                .model(appProperties.getDashscope().getModel())
                .maxIters(PoiResearchAgent.MAX_ITERS)
                .workspaceMode(WorkspaceMode.SHARED)
                .skills(List.of("attraction-search"))
                .build();

        // 路线调优子 Agent：读候选清单迭代排线，产出 route_plan.md
        SubagentDeclaration routeOptimizer = SubagentDeclaration.builder()
                .name(RouteOptimizerAgent.AGENT_NAME)
                .description("路线调优 Agent（排线员）：读 poi_shortlist.md，两两调路线工具算通勤，"
                        + "就近聚类分日 + 迭代调整再算，产出 route_plan.md")
                .inlineAgentsBody(RouteOptimizerAgent.SYS_PROMPT)
                .model(appProperties.getDashscope().getModel())
                .maxIters(RouteOptimizerAgent.MAX_ITERS)
                .workspaceMode(WorkspaceMode.SHARED)
                .build();

        // 质检子 Agent：5 维评分 + 可调工具核验事实，产出 review_passed.md / review_report.md
        SubagentDeclaration reviewer = SubagentDeclaration.builder()
                .name(ReviewerAgent.AGENT_NAME)
                .description("质量审阅 Agent（质检员）：对 itinerary_draft.md 做 5 维评分"
                        + "（完备性/可行性/时间冲突/费用预算/POI 合理性，各 20 分），"
                        + "可调工具核验事实，通过写 review_passed.md，不通过写 review_report.md")
                .inlineAgentsBody(ReviewerAgent.SYS_PROMPT)
                .model(appProperties.getDashscope().getModel())
                .maxIters(ReviewerAgent.MAX_ITERS)
                .workspaceMode(WorkspaceMode.SHARED)
                .build();

        try {
            return HarnessAgent.builder()
                    .name(ItineraryAgent.AGENT_NAME)
                    .sysPrompt(ItineraryAgent.SYS_PROMPT)
                    .model(dashscopeChatModel)
                    .modelResolver(name -> dashscopeChatModel)
                    .toolkit(toolkit)
                    .maxIters(ItineraryAgent.MAX_ITERS)
                    .permissionContext(PermissionContextState.builder()
                            .mode(PermissionMode.BYPASS)
                            .build())
                    .workspace(appProperties.getAgentscope().getWorkspacePath())
                    .skillRepository(new ClasspathSkillRepository("skills"))
                    .stateStore(new InMemoryAgentStateStore())
                    .subagents(List.of(poiResearch, routeOptimizer, reviewer))
                    .build();
        } catch (IOException e) {
            // subagentFactory 的 Function 不允许抛受检异常，转非受检
            throw new IllegalStateException("构建 planning-agent 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 创建意图分类器 Bean（FR-S01 三层级联的 L3 兜底层）
     * <p>
     * 轻量 ReActAgent（无工具无子代理），对用户消息做结构化意图分类。
     * v3 起不再被 ChatService 直接调用，而是被 IntentCascadeRouter 以方法引用
     * 包装为 L3 兜底（本类逻辑零改动）；L0~L2 未命中或级联关闭时才到达这里。
     * </p>
     */
    @Bean
    public IntentClassifier intentClassifier(
            @org.springframework.beans.factory.annotation.Qualifier("dashscopeChatModel")
            DashScopeChatModel dashscopeChatModel) {
        return new IntentClassifier(dashscopeChatModel);
    }

    /**
     * 创建意图级联路由器 Bean（FR-S01：L0 → L1 → L2 → L3，上层命中即短路）
     * <p>
     * L2 注入 qwen-turbo 轻量分类器；L3 以方法引用包装现有 IntentClassifier（零改动）；
     * 缓存为 RedisIntentCache（Redis 不可用时静默降级为无缓存模式）。
     * </p>
     */
    @Bean
    public IntentCascadeRouter intentCascadeRouter(
            IntentCache intentCache,
            IntentClassifier intentClassifier,
            @org.springframework.beans.factory.annotation.Qualifier("dashscopeTurboModel")
            DashScopeChatModel dashscopeTurboModel) {
        LightweightIntentClassifier l2 = new LightweightIntentClassifier(dashscopeTurboModel);
        return new IntentCascadeRouter(appProperties, intentCache,
                l2::classify, intentClassifier::classify);
    }

    /**
     * 创建共享任务区间服务 Bean
     */
    @Bean
    public TaskWorkspaceService taskWorkspaceService() {
        return new TaskWorkspaceService(appProperties);
    }

    /**
     * 共享任务区间服务
     * <p>
     * 管理主 Agent 与各子 Agent 之间的共享任务区间（一组 Markdown 文件）。
     * 路径按 <b>用户 + 会话</b> 双级隔离（需求 1），且与 harness 的用户目录约定对齐：
     * Agent 的文件工具会把相对路径解析到 {@code {workspacePath}/{userId}/} 之下，
     * 因此磁盘路径为：
     * <pre>
     * {workspacePath}/{userId}/tasks/{sessionId}/
     *   ├── intake_done.md      intake-agent 写入的需求收集结果
     *   ├── task_backlog.md     主 Agent 经 create_task_backlog 登记的任务清单
     *   ├── poi_shortlist.md    poi-research 产出的景点候选清单
     *   ├── route_plan.md       route-optimizer 产出的分日路线方案
     *   ├── execution_result.md 规划 Agent 写回的执行结果
     *   ├── itinerary_draft.md  规划 Agent 生成的行程草案
     *   ├── review_report.md    reviewer-agent 不通过时的质检报告
     *   ├── review_passed.md    reviewer-agent 通过时的凭证
     *   └── session_meta.md     会话元信息
     * </pre>
     * Agent 侧使用相对路径 {@code tasks/{sessionId}/...} 访问，天然用户隔离。
     * </p>
     */
    public static class TaskWorkspaceService {

        private static final Logger wsLog = LoggerFactory.getLogger(TaskWorkspaceService.class);

        private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        public static final String FILE_INTAKE_DONE = "intake_done.md";
        public static final String FILE_TASK_BACKLOG = "task_backlog.md";
        public static final String FILE_POI_SHORTLIST = "poi_shortlist.md";
        public static final String FILE_ROUTE_PLAN = "route_plan.md";
        public static final String FILE_EXECUTION_RESULT = "execution_result.md";
        public static final String FILE_ITINERARY_DRAFT = "itinerary_draft.md";
        public static final String FILE_REVIEW_REPORT = "review_report.md";
        public static final String FILE_REVIEW_PASSED = "review_passed.md";
        public static final String FILE_SESSION_META = "session_meta.md";

        private final String workspaceRoot;

        public TaskWorkspaceService(AppProperties appProperties) {
            this.workspaceRoot = appProperties.getAgentscope().getWorkspacePath();
        }

        // ==================== 核心方法 ====================

        /**
         * 初始化任务区间（创建目录 + 元信息文件）
         *
         * @param userId    用户 ID（隔离第一级）
         * @param sessionId 会话 ID（隔离第二级）
         * @return 任务区间根路径
         */
        public Path initTaskWorkspace(String userId, String sessionId) {
            Path dir = getTaskDir(userId, sessionId);
            try {
                Files.createDirectories(dir);
                String meta = """
                        # 会话元信息

                        - 用户ID: %s
                        - 会话ID: %s
                        - 创建时间: %s
                        - 状态: 进行中
                        - 参与者: travel-master, intake-agent, planning-agent, poi-research, route-optimizer, reviewer-agent
                        """.formatted(userId, sessionId, LocalDateTime.now().format(FMT));
                writeFile(userId, sessionId, FILE_SESSION_META, meta);
                wsLog.info("任务区间初始化: 用户={}, 会话={}, dir={}", userId, sessionId, dir);
                return dir;
            } catch (IOException e) {
                wsLog.error("初始化任务区间失败: 用户={}, 会话={}", userId, sessionId, e);
                throw new RuntimeException("初始化任务区间失败: " + e.getMessage(), e);
            }
        }

        /**
         * 写入任务清单（task_backlog.md）—— create_task_backlog 工具调用
         *
         * @return 实际落盘文件路径
         */
        public Path writeTaskBacklog(String userId, String sessionId, String content) {
            return Path.of(writeFile(userId, sessionId, FILE_TASK_BACKLOG, content));
        }

        /**
         * 读取任务清单（task_backlog.md）
         */
        public String readTaskBacklog(String userId, String sessionId) {
            return readFile(userId, sessionId, FILE_TASK_BACKLOG);
        }

        /**
         * 任务清单在 Agent 工作区中的相对路径（注入路由指令 / 委派说明使用）
         * <p>Agent 的文件工具相对路径以 {workspace}/{userId}/ 为根，因此无需用户前缀。</p>
         */
        public String backlogRelativePath(String sessionId) {
            return "tasks/" + sessionId + "/" + FILE_TASK_BACKLOG;
        }

        /**
         * 会话协作目录在 Agent 工作区中的相对路径
         */
        public String collabDirRelativePath(String sessionId) {
            return "tasks/" + sessionId;
        }

        /**
         * 追加执行结果（execution_result.md）—— 规划 Agent 调用
         */
        public void appendExecutionResult(String userId, String sessionId, String taskId, String result) {
            String block = """

                    ## 执行结果: %s
                    - 时间: %s

                    %s

                    ---
                    """.formatted(taskId, LocalDateTime.now().format(FMT), result);

            Path file = getTaskDir(userId, sessionId).resolve(FILE_EXECUTION_RESULT);
            try {
                if (!Files.exists(file)) {
                    String header = "# 执行结果记录\n\n会话ID: " + sessionId + "\n";
                    Files.writeString(file, header + block, StandardCharsets.UTF_8);
                } else {
                    Files.writeString(file, block, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                }
                wsLog.info("执行结果已追加: 用户={}, 会话={}, taskId={}", userId, sessionId, taskId);
            } catch (IOException e) {
                wsLog.error("写入执行结果失败: 用户={}, 会话={}, taskId={}", userId, sessionId, taskId, e);
            }
        }

        /**
         * 读取执行结果（execution_result.md）—— 主 Agent 调用
         */
        public String readExecutionResult(String userId, String sessionId) {
            return readFile(userId, sessionId, FILE_EXECUTION_RESULT);
        }

        /**
         * 写入行程草案（itinerary_draft.md）—— 规划 Agent 调用
         */
        public void writeItineraryDraft(String userId, String sessionId, String content) {
            writeFile(userId, sessionId, FILE_ITINERARY_DRAFT, content);
            updateSessionStatus(userId, sessionId, "已完成");
            wsLog.info("行程草案已写入: 用户={}, 会话={}", userId, sessionId);
        }

        /**
         * 读取行程草案（itinerary_draft.md）—— 主 Agent 调用
         */
        public String readItineraryDraft(String userId, String sessionId) {
            return readFile(userId, sessionId, FILE_ITINERARY_DRAFT);
        }

        // ==================== 工具方法 ====================

        /**
         * 任务区间目录：{workspaceRoot}/{userId}/tasks/{sessionId}
         * <p>与 harness 的用户目录约定对齐（Agent 相对路径以 {workspace}/{userId}/ 为根）。</p>
         */
        public Path getTaskDir(String userId, String sessionId) {
            return Paths.get(workspaceRoot, String.valueOf(userId), "tasks", sessionId);
        }

        private String writeFile(String userId, String sessionId, String fileName, String content) {
            Path dir = getTaskDir(userId, sessionId);
            try {
                Files.createDirectories(dir);
                Path file = dir.resolve(fileName);
                Files.writeString(file, content, StandardCharsets.UTF_8);
                return file.toString();
            } catch (IOException e) {
                wsLog.error("写入文件失败: 用户={}, 会话={}, file={}", userId, sessionId, fileName, e);
                throw new RuntimeException("写入文件失败: " + e.getMessage(), e);
            }
        }

        private String readFile(String userId, String sessionId, String fileName) {
            Path file = getTaskDir(userId, sessionId).resolve(fileName);
            if (!Files.exists(file)) {
                wsLog.warn("文件不存在: 用户={}, 会话={}, file={}", userId, sessionId, fileName);
                return null;
            }
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                wsLog.error("读取文件失败: 用户={}, 会话={}, file={}", userId, sessionId, fileName, e);
                return null;
            }
        }

        private void updateSessionStatus(String userId, String sessionId, String status) {
            Path file = getTaskDir(userId, sessionId).resolve(FILE_SESSION_META);
            try {
                if (Files.exists(file)) {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    content = content.replaceAll("状态: .*", "状态: " + status);
                    content += "\n- 更新时间: " + LocalDateTime.now().format(FMT);
                    Files.writeString(file, content, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                wsLog.error("更新会话状态失败: 用户={}, 会话={}", userId, sessionId, e);
            }
        }
    }
}
