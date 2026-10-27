package com.travelscope.config;

import com.travelscope.agent.IntentCascadeRouter;
import com.travelscope.agent.IntentClassifier;
import com.travelscope.agent.IntentRouterMiddleware;
import com.travelscope.agent.ItineraryAgent;
import com.travelscope.agent.LightweightIntentClassifier;
import com.travelscope.agent.ReviewerAgent;
import com.travelscope.agent.ReviewerRetryMiddleware;
import com.travelscope.agent.TravelMasterAgent;
import com.travelscope.collab.TaskWorkspace;
import com.travelscope.agent.tools.AttractionTool;
import com.travelscope.agent.tools.HotelTool;
import com.travelscope.agent.tools.TaskTools;
import com.travelscope.agent.tools.TransportTool;
import com.travelscope.agent.tools.WeatherTool;
import com.travelscope.common.TracingHelper;
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
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.glm.GLMFormatter;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.harness.agent.HarnessAgent;
import com.travelscope.llm.EmbeddingProviderPool;
import com.travelscope.llm.ModelProviderPool;
import com.travelscope.llm.ProviderCircuitBreaker;
import com.travelscope.llm.ProviderProbeScheduler;
import com.travelscope.service.LlmGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AgentScope 运行时配置。
 * 唯一生产根 travel-master 处理直接请求；完整规划时依次委派 planning-agent 和
 * reviewer-agent 两个叶子。reviewer 内部调用 Java 规则工具完成复检闭环；ChatService 负责
 * 需求采集、任务登记、最终产物验收与渲染。
 * 在线与评测使用相同的父子拓扑，评测根实例使用独立状态。
 */
@Configuration
public class AgentConfig {
    @org.springframework.beans.factory.annotation.Autowired
    private com.travelscope.assessment.ObservationStore observationStore;
    @org.springframework.beans.factory.annotation.Autowired
    private com.travelscope.assessment.ObservationMiddleware observationMiddleware;


    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    private final AppProperties appProperties;
    private final WeatherTool weatherTool;
    private final HotelTool hotelTool;
    private final AttractionTool attractionTool;
    private final TransportTool transportTool;
    /** 飞猪火车/机票工具（2026-10-17 飞猪比赛接入） */
    private final com.travelscope.agent.tools.TrainTicketTool trainTicketTool;
    private final com.travelscope.agent.tools.FlightTicketTool flightTicketTool;
    /** 业务链路打点（多智能体 span 的统一装配，2026-10-02） */
    private final TracingHelper tracingHelper;

    public AgentConfig(AppProperties appProperties,
                       WeatherTool weatherTool,
                       HotelTool hotelTool,
                       AttractionTool attractionTool,
                       TransportTool transportTool,
                       com.travelscope.agent.tools.TrainTicketTool trainTicketTool,
                       com.travelscope.agent.tools.FlightTicketTool flightTicketTool,
                       TracingHelper tracingHelper) {
        this.appProperties = appProperties;
        this.weatherTool = weatherTool;
        this.hotelTool = hotelTool;
        this.attractionTool = attractionTool;
        this.transportTool = transportTool;
        this.trainTicketTool = trainTicketTool;
        this.flightTicketTool = flightTicketTool;
        this.tracingHelper = tracingHelper;
    }

    /**
     * 创建 Toolkit（工具集）
     * <p>
     * 注册天气查询、酒店搜索、景点搜索、市内交通、任务缓存和协作契约工具，
     * 并通过 MCP 客户端接入火车票（12306）与飞机票（飞常准）实时查询工具，
     * 供主 Agent 和各子 Agent（SHARED 工作区、共享 Toolkit）共同使用。
     * </p>
     */
    @Bean
    public Toolkit travelToolkit(TaskRegistry taskRegistry,
                                 com.travelscope.service.TaskResultCache taskResultCache,
                                 com.travelscope.service.RagServiceImpl ragServiceImpl,
                                 com.travelscope.agent.tools.CollabTools collabTools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(weatherTool);
        toolkit.registerTool(hotelTool);
        toolkit.registerTool(attractionTool);
        toolkit.registerTool(transportTool);
        // 飞猪火车/机票工具（与 12306/飞常准 MCP 双源并存，返回标注【飞猪】来源）
        toolkit.registerTool(trainTicketTool);
        toolkit.registerTool(flightTicketTool);
        // 任务容器工具：create_task_backlog / get_task_progress / update_task_status
        // 主 Agent 与规划子 Agent（SHARED 工作区、共享 Toolkit）均可调用
        toolkit.registerTool(new TaskTools(taskRegistry, tracingHelper));
        // RAG 双路检索 + 任务结果缓存工具（FR-S06/S11/S14）：
        // search_pois_with_rag（poi-research 双路召回）+ get/register_task_result（planner 缓存复用）
        toolkit.registerTool(new com.travelscope.agent.tools.PoiRagTools(ragServiceImpl, taskResultCache, tracingHelper));
        // 规划协作工具：POI 检索/定案、路线求解、草案组装与评审产物写入。
        toolkit.registerTool(collabTools);
        registerMcpClients(toolkit);
        log.info("Toolkit 注册完成: 天气/酒店/景点/交通 + 任务容器 + RAG/任务缓存 + 规划协作工具 + MCP(12306、飞常准)");
        return toolkit;
    }

    /**
     * 规划需求状态仓库，按 userId/sessionId 隔离。Redis 不可用时自动降级为进程内存。
     */
    @Bean
    public TripRequirementStore tripRequirementStore(
            org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate) {
        return new TripRequirementStore(stringRedisTemplate);
    }

    /**
     * 任务结果缓存 Bean（FR-S14：子任务产出登记/复用，为 P7 局部回炉打底）
     * <p>
     * Redis key {@code taskresult:{userId}:{sessionId}:{taskType}}，
     * TTL 按类型（POI/路线/酒店 30min / 天气 10min）；降级进程内存。
     * </p>
     */
    @Bean
    public com.travelscope.service.TaskResultCache taskResultCache(
            org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate) {
        return new com.travelscope.service.TaskResultCache(stringRedisTemplate);
    }

    /**
     * 任务容器注册表（用户+会话双级隔离，见 TaskRegistry）
     */
    @Bean
    public TaskRegistry taskRegistry(TaskWorkspace taskWorkspaceService) {
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
     * 主链路模型池 Bean（v5-F3 main 池，@Primary：master/planner/意图 L2+L3/需求抽取/会话摘要）
     * <p>
     * 2026-10-22 多模型熔断池改造：原「ObservedModel(OpenAIChatModel) + LlmGatewayModel 单
     * fallback 装饰」泛化为 N 路 provider 池（{@link ModelProviderPool}）。providers 来自
     * {@code travelscope.model-pool.chat.main}（当前 glm-5-3-260814 优先 + Kimi-k3 兜底，同网关
     * 仅 model id 不同）；per-provider：独立熔断器（超时立即 OPEN / 统计口径 OPEN / 探针恢复）、
     * 首包+块间超时分段、use-glm-formatter 差异（GLM 系才挂 GLMFormatter + thinking 透传）。
     * 池语义（选路/首包提交闸门/能力委托）见 {@link ModelProviderPool}；探针注册见
     * {@link ProviderProbeScheduler}。旧 LlmGatewayModel 的粘滞 fallback + 30s ping 回切退役，
     * 语义并入「池内 failover + OPEN 冷却探针」。model-pool 未配置时回落 model-gateway 单模型
     * （GLMFormatter 兼容默认，测试/最小部署用）。
     * </p>
     * <p>
     * 返回类型为 {@link Model} 接口（装饰器模式；框架核验：Model 仅 2 抽象方法，
     * 框架自身的 fallbackModel 实现就是同款 implements Model 包装器）。
     * @Primary：按 Model 类型注入的下游解析到本池。
     * </p>
     */
    @Bean
    @org.springframework.context.annotation.Primary
    public Model gatewayChatModel(ProviderProbeScheduler probeScheduler) {
        return buildChatPool("main", appProperties.getModelPool().getChat().getMain(), probeScheduler);
    }

    /**
     * LLM Gateway 对话准入层 Bean（FR-S09：全局/单用户并发 + QPS + 泳道超时；
     * v5-F3 起模型层熔断/failover 迁入 model-pool 池装饰器，准入层职责不变）
     */
    @Bean
    public LlmGateway llmGateway() {
        return new LlmGateway(appProperties);
    }

    /**
     * 质检评分模型池 Bean（v5-F3 reviewer 池：standaloneReviewer / planner modelResolver 分流 /
     * RubricScorer / PosthocScorer，独立列表独立熔断，将来换更强模型只改本池配置）
     * <p>
     * providers 来自 {@code travelscope.model-pool.chat.reviewer}（当前 glm 优先 +
     * kimi-k3 兜底）；未配置时回落 model-gateway.reviewer-model 单模型（GLMFormatter）。
     * </p>
     */
    @Bean
    public Model gatewayReviewerModel(ProviderProbeScheduler probeScheduler) {
        return buildChatPool("reviewer", appProperties.getModelPool().getChat().getReviewer(), probeScheduler);
    }

    /**
     * 讲解专用模型池 Bean（私人讲解员图片轮）：多模态单轮调用专用——glm-5-3 无视觉，
     * 池内固定 kimi-k3（网关强制开思考、看图慢，独立池与主链路互不拖累）。
     * ChatService.guideEventStream 消费；未配置时回落 model-gateway 单模型。
     */
    @Bean
    public Model guideModelPool(ProviderProbeScheduler probeScheduler) {
        return buildChatPool("guide", appProperties.getModelPool().getChat().getGuide(), probeScheduler);
    }

    /**
     * 构建一个 chat 模型池（v5-F3 统一装配）：逐 provider 建 ObservedModel(OpenAIChatModel)
     * + 独立熔断器 + 超时覆盖，并注册探针目标（OPEN 冷却到期由调度器 CAS 抢单发最小探测）。
     */
    private ModelProviderPool buildChatPool(String poolName, AppProperties.ChatPoolSpec spec,
                                            ProviderProbeScheduler probes) {
        var mg = appProperties.getModelGateway();
        var bc = appProperties.getModelPool().getBreaker();
        List<AppProperties.ProviderSpec> specs = spec.getProviders();
        if (specs == null || specs.isEmpty()) {
            // model-pool 未配置：回落 model-gateway 单模型（GLM + GLMFormatter 兼容默认）
            specs = List.of(legacyChatProviderSpec(
                    "reviewer".equals(poolName) ? mg.getReviewerModel() : mg.getModel()));
        }
        List<ModelProviderPool.Provider> providers = new java.util.ArrayList<>();
        for (AppProperties.ProviderSpec p : specs) {
            OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                    .baseUrl(p.getBaseUrl() == null || p.getBaseUrl().isBlank()
                            ? mg.getBaseUrl() : p.getBaseUrl())
                    .apiKey(p.getApiKey() != null && !p.getApiKey().isBlank()
                            ? p.getApiKey() : mg.getApiKey())
                    .modelName(p.getModel())
                    .stream(true)
                    .nativeStructuredOutput(p.getNativeStructuredOutput() != null
                            ? p.getNativeStructuredOutput() : mg.isNativeStructuredOutput())
                    .nativeStructuredOutputWithTools(p.getNativeStructuredOutputWithTools() != null
                            ? p.getNativeStructuredOutputWithTools() : mg.isNativeStructuredOutputWithTools());
            if (p.isUseGlmFormatter()) {
                // GLM 系专属：GLMFormatter + thinking 参数透传；非 GLM（Kimi-k3 等）走网关默认格式
                builder.formatter(new GLMFormatter()).generateOptions(glmDefaults(mg));
            }
            Model raw = new com.travelscope.assessment.ObservedModel(builder.build(), observationStore);
            ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(
                    poolName + "/" + p.getId(), bc.getMinCalls(), bc.getMinSuccessRate(),
                    Duration.ofSeconds(p.getOpenWaitSeconds() != null
                            ? p.getOpenWaitSeconds() : bc.getOpenWaitSeconds()).toMillis());
            ModelProviderPool.Provider provider = new ModelProviderPool.Provider(
                    p.getId(), p.getPriority(), raw, breaker,
                    Duration.ofSeconds(p.getFirstPacketTimeoutSeconds() != null
                            ? p.getFirstPacketTimeoutSeconds() : bc.getFirstPacketTimeoutSeconds()),
                    Duration.ofSeconds(p.getChunkTimeoutSeconds() != null
                            ? p.getChunkTimeoutSeconds() : bc.getChunkTimeoutSeconds()));
            providers.add(provider);
            registerProbeTarget(probes, breaker, () -> ModelProviderPool.probe(provider));
        }
        log.info("模型池 {} 构建完成: {}", poolName, providers.stream()
                .map(p -> p.id() + "(" + p.raw().getModelName() + ",priority=" + p.priority() + ")").toList());
        return new ModelProviderPool(poolName, providers, tracingHelper);
    }

    /** model-pool 缺省时的兼容单 provider（model-gateway 主/质检模型 + GLMFormatter） */
    private static AppProperties.ProviderSpec legacyChatProviderSpec(String model) {
        AppProperties.ProviderSpec s = new AppProperties.ProviderSpec();
        s.setId("default");
        s.setModel(model);
        s.setPriority(1);
        s.setUseGlmFormatter(true);
        return s;
    }

    /** 探针目标注册（breaker 冷却到期 → 调度器 CAS 抢单 → 对该 provider 发最小探测） */
    private static void registerProbeTarget(ProviderProbeScheduler probes,
                                            ProviderCircuitBreaker breaker, Runnable action) {
        probes.register(new ProviderProbeScheduler.ProbeTarget() {
            @Override public ProviderCircuitBreaker breaker() { return breaker; }
            @Override public void probe() { action.run(); }
        });
    }

    /**
     * GLM 系模型的默认请求参数：thinking 开关经 additionalBodyParam 原样透传到请求体
     * （框架 GLMFormatter 的官方机制）。GLM-5 系默认开启思考、单轮推理实测 35~133s
     * （planner 链路 420s 预算内连 poi-research 都未跑完），禁用后单轮 ~2s——
     * 默认禁用，经 travelscope.model-gateway.thinking-enabled 可开。
     */
    private static GenerateOptions glmDefaults(AppProperties.ModelGatewayConfig mg) {
        return GenerateOptions.builder()
                .additionalBodyParam("thinking",
                        Map.of("type", mg.isThinkingEnabled() ? "enabled" : "disabled"))
                .build();
    }

    /** 唯一生产 Agent：处理直接请求，并为完整规划依次委派两个叶子 Agent。 */
    @Bean
    @org.springframework.context.annotation.Primary
    public HarnessAgent travelMasterAgent(Toolkit toolkit, Model gatewayChatModel, Model gatewayReviewerModel,
                                           TaskWorkspace taskWorkspace, TaskRegistry taskRegistry,
                                           com.travelscope.service.TaskResultCache taskResultCache,
                                           com.travelscope.service.RuleReviewService ruleReviewService,
                                           io.agentscope.core.state.AgentStateStore travelAgentStateStore) throws IOException {
        return buildMasterAgent(toolkit, gatewayChatModel, gatewayReviewerModel, travelAgentStateStore,
                taskWorkspace, taskRegistry, taskResultCache, ruleReviewService);
    }

    @Bean
    @org.springframework.context.annotation.Scope("prototype")
    public HarnessAgent assessmentMasterAgent(Toolkit toolkit,
            @org.springframework.beans.factory.annotation.Qualifier("gatewayChatModel") Model mainModel,
            @org.springframework.beans.factory.annotation.Qualifier("gatewayReviewerModel") Model reviewerModel,
            TaskWorkspace taskWorkspace, TaskRegistry taskRegistry,
            com.travelscope.service.TaskResultCache taskResultCache,
            com.travelscope.service.RuleReviewService ruleReviewService) throws IOException {
        return buildMasterAgent(toolkit.copy(), mainModel, reviewerModel, new InMemoryAgentStateStore(),
                taskWorkspace, taskRegistry, taskResultCache, ruleReviewService);
    }

    private HarnessAgent buildMasterAgent(Toolkit toolkit, Model gatewayChatModel,
            Model gatewayReviewerModel,
            io.agentscope.core.state.AgentStateStore travelAgentStateStore,
            TaskWorkspace taskWorkspace, TaskRegistry taskRegistry,
            com.travelscope.service.TaskResultCache taskResultCache,
            com.travelscope.service.RuleReviewService ruleReviewService) throws IOException {
        HarnessAgent agent = HarnessAgent.builder()
                .name(TravelMasterAgent.AGENT_NAME)
                .sysPrompt(TravelMasterAgent.FULL_SYS_PROMPT)
                .model(gatewayChatModel)
                .toolkit(toolkit)
                .maxIters(TravelMasterAgent.MAX_ITERS)
                // 挂起的工具调用自动补错误结果继续（2026-09-21）：长规划链路被泳道超时/客户端断开/
                // 同会话并发打断后，会话状态残留 pending tool_use（无 result）——默认 false 时该会话
                // 后续每条消息都抛 IllegalStateException（Pending tool calls exist without results），
                // 会话永久卡死。开启后框架自动为 pending 调用生成错误结果，对话可继续。
                .enablePendingToolRecovery(true)
                // 路由中间件按已分类意图限制工具行为；观测中间件记录模型与工具事件。
                .middlewares(List.of(observationMiddleware, new IntentRouterMiddleware()))
                // 服务端受信工具使用 BYPASS，避免无人值守调用等待交互式确认；
                // 写操作仍由具体工具的白名单和 Schema 校验约束。
                .permissionContext(PermissionContextState.builder()
                        .mode(PermissionMode.BYPASS)
                        .build())
                .workspace(appProperties.getAgentscope().getWorkspacePath())
                .skillRepository(new ClasspathSkillRepository("skills"))
                // 会话状态存储（2026-09-23 并发改造）：RedisAgentStateStore（Bean 由
                // RedisStateStoreConfig 提供，Redis 不可用自动降级 InMemory）——2000 用户下
                // 状态不占应用堆 + 进程重启不失忆；替代原先直接 new InMemoryAgentStateStore()
                .stateStore(travelAgentStateStore)
                .subagentFactory(ItineraryAgent.AGENT_NAME,
                        "叶子行程规划 Agent：查询实时数据、筛选 POI、规划路线并组装 itinerary_draft.json",
                        name -> {
                            HarnessAgent child = buildPlannerAgent(toolkit, gatewayChatModel,
                                    taskRegistry, taskResultCache, taskWorkspace, true);
                            revokeOffScriptTools(child);
                            return child;
                        })
                .subagentFactory(ReviewerAgent.AGENT_NAME,
                        "叶子质量审阅 Agent：审查并按需修订草案，写入结构化质检产物",
                        name -> buildReviewerAgent(toolkit, gatewayReviewerModel, taskWorkspace,
                                ruleReviewService, true))
                .build();

        log.info("主 Agent 构建完成: {} (workspace={})", TravelMasterAgent.AGENT_NAME,
                appProperties.getAgentscope().getWorkspacePath());
        return agent;
    }

    /** 构建由 travel-master factory 创建的规划 Agent；leaf=true 时禁止继续委派。 */
    private HarnessAgent buildPlannerAgent(Toolkit toolkit, Model gatewayChatModel,
                                           TaskRegistry taskRegistry,
                                           com.travelscope.service.TaskResultCache taskResultCache,
                                           com.travelscope.collab.TaskWorkspace taskWorkspace,
                                           boolean leaf) {
        var bookkeeping = new com.travelscope.agent.BookkeepingMiddleware(
                taskRegistry, taskResultCache, taskWorkspace);
        try {
            HarnessAgent.Builder builder = HarnessAgent.builder()
                    .name(ItineraryAgent.AGENT_NAME)
                    .middlewares(List.of(observationMiddleware,
                            new com.travelscope.agent.AgentStageResultMiddleware(taskWorkspace,
                                    com.travelscope.collab.AgentStageResult.Stage.PLANNER),
                            new com.travelscope.agent.PlanningToolGuardMiddleware(taskWorkspace), bookkeeping))
                    .sysPrompt(ItineraryAgent.SYS_PROMPT)
                    .model(gatewayChatModel)
                    .toolkit(plannerToolkitView(toolkit))
                    .maxIters(ItineraryAgent.MAX_ITERS)
                    .enablePendingToolRecovery(true)
                    .permissionContext(PermissionContextState.builder()
                            .mode(PermissionMode.BYPASS)
                            .build())
                    .workspace(appProperties.getAgentscope().getWorkspacePath())
                    .skillRepository(new ClasspathSkillRepository("skills"))
                    .stateStore(new InMemoryAgentStateStore());
            if (leaf) {
                builder.disableSubagents();
            }
            return builder.build();
        } catch (IOException e) {
            throw new IllegalStateException("构建 planning-agent 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 移除框架默认提供但会绕过契约校验或数据来源约束的工具。read_file 保留给缺少内联输入时
     * 的精确文件回退；规划和评审产物只能通过白名单中的契约工具写入。
     */
    public static void revokeOffScriptTools(HarnessAgent agent) {
        Toolkit delegate = agent.getDelegate().getToolkit();
        for (String name : new String[]{"edit_file", "write_file", "web_search", "web_fetch",
                "execute"}) {
            delegate.removeTool(name);
        }
    }

    private HarnessAgent buildReviewerAgent(Toolkit toolkit, Model gatewayReviewerModel,
            TaskWorkspace taskWorkspace, com.travelscope.service.RuleReviewService ruleReviewService,
            boolean leaf) {
        Toolkit view = toolkit.copy();
        view.registerTool(new com.travelscope.agent.tools.ReviewerRuleTools(ruleReviewService));
        for (String name : new java.util.ArrayList<>(view.getToolNames())) {
            if (!REVIEWER_TOOL_WHITELIST.contains(name)) {
                view.removeTool(name);
            }
        }
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(ReviewerAgent.AGENT_NAME)
                .sysPrompt(ReviewerAgent.SYS_PROMPT)
                .model(gatewayReviewerModel)
                .toolkit(view)
                .maxIters(ReviewerAgent.MAX_ITERS)
                .middlewares(List.of(observationMiddleware,
                        new com.travelscope.agent.AgentStageResultMiddleware(taskWorkspace,
                                com.travelscope.collab.AgentStageResult.Stage.REVIEWER),
                        new ReviewerRetryMiddleware(taskWorkspace, tracingHelper)))
                .enablePendingToolRecovery(true)
                .permissionContext(PermissionContextState.builder()
                        .mode(PermissionMode.BYPASS).build())
                .workspace(appProperties.getAgentscope().getWorkspacePath())
                .stateStore(new InMemoryAgentStateStore());
        if (leaf) {
            builder.disableSubagents();
        }
        HarnessAgent agent = builder.build();
        revokeOffScriptTools(agent);
        return agent;
    }

    /** 独立修订 Agent 的工具白名单。 */
    private static final java.util.Set<String> REVIEWER_TOOL_WHITELIST = java.util.Set.of(
            "register_task_result", "get_cached_task_result",
            "plan_route", "searchHotels", "retrieve_poi_candidates",
            "revise_poi_selection", "assemble_itinerary", "review_retry_hint",
            com.travelscope.agent.tools.ReviewerRuleTools.TOOL_NAME);


    /**
     * planning-agent 工具白名单：实时数据查询、POI 名单定案、路线求解、草案组装和任务缓存。
     * MCP 工具按 {@code mcp__*} 前缀放行。
     */
    private static final java.util.Set<String> PLANNER_TOOL_WHITELIST = java.util.Set.of(
            "getWeather", "getWeatherForecast", "searchHotels",
            "getDrivingRoute", "getTransitRoute", "geocode",
            "queryTrainTickets", "queryFlightTickets",
            "retrieve_poi_candidates", "select_poi_shortlist", "plan_route", "write_collab_file",
            "assemble_itinerary",
            // register_task_result 保留：reviewer 回炉修订自登记仍走工具；
            // update_task_status 已移除——planner 的任务状态由 BookkeepingMiddleware 代码副作用维护
            "register_task_result", "get_cached_task_result",
            "get_task_progress",
            "planning_guard_hint", "review_retry_hint");

    /** 共享 toolkit 的 planner 视图：copy 后按白名单剪枝（mcp__* 前缀放行；agent_spawn/task_*
     *  由 SubagentsMiddleware 内置注册，不经 toolkit，不受剪枝影响） */
    private static Toolkit plannerToolkitView(Toolkit shared) {
        Toolkit view = shared.copy();
        for (String name : new java.util.ArrayList<>(view.getToolNames())) {
            if (!PLANNER_TOOL_WHITELIST.contains(name) && !name.startsWith("mcp__")) {
                view.removeTool(name);
            }
        }
        // select_poi_shortlist 等遗留工具返回 String；将 ERROR: 文本提升为框架 ERROR 状态，
        // 让模型和中间件都能正确识别失败，而不是 SUCCESS + 错误文本。
        wrapErrorTextTool(view, "select_poi_shortlist");
        return view;
    }

    private static void wrapErrorTextTool(Toolkit toolkit, String name) {
        io.agentscope.core.tool.AgentTool delegate = toolkit.getTool(name);
        if (delegate == null) return;
        toolkit.removeTool(name);
        toolkit.registerAgentTool(new com.travelscope.agent.tools.ErrorStateToolWrapper(delegate));
    }

    /** 创建无工具、无子 Agent 的 L3 意图分类器，由 IntentCascadeRouter 在前层未命中时调用。 */
    @Bean
    public IntentClassifier intentClassifier(
            @org.springframework.beans.factory.annotation.Qualifier("gatewayChatModel")
            Model gatewayChatModel) {
        IntentClassifier classifier = new IntentClassifier(gatewayChatModel);
        classifier.setObservation(observationMiddleware);
        return classifier;
    }

    /**
     * 创建 L0 → L1 → L2 → L3 的短路意图路由器。L2 使用主模型池做轻量分类，L3 调用
     * IntentClassifier；Redis 意图缓存不可用时自动退化为无缓存模式。
     */
    @Bean
    public IntentCascadeRouter intentCascadeRouter(
            IntentCache intentCache,
            IntentClassifier intentClassifier,
            TaskWorkspace taskWorkspaceService,
            @org.springframework.beans.factory.annotation.Qualifier("gatewayChatModel")
            Model mainModelPool) {
        LightweightIntentClassifier l2 = new LightweightIntentClassifier(mainModelPool);
        l2.setObservation(observationMiddleware);
        return new IntentCascadeRouter(appProperties, intentCache,
                new IntentCascadeRouter.LlmClassifier() {
                    public com.travelscope.dto.IntentResult classify(String m,String c,String u,String s) {return l2.classify(m,c,u,s);}
                    public com.travelscope.dto.IntentResult classify(String m,String c,String u,String s,com.travelscope.assessment.ObservationContext o) {return l2.classify(m,c,u,s,o);}
                }, new IntentCascadeRouter.LlmClassifier() {
                    public com.travelscope.dto.IntentResult classify(String m,String c,String u,String s) {return intentClassifier.classify(m,c,u,s);}
                    public com.travelscope.dto.IntentResult classify(String m,String c,String u,String s,com.travelscope.assessment.ObservationContext o) {return intentClassifier.classify(m,c,u,s,o);}
                },
                // v7 第六章 L0 完成态守卫：会话协作目录有行程草案 = 规划完成态
                (uid, sid) -> taskWorkspaceService.readItineraryDraft(uid, sid).isPresent());
    }

}
