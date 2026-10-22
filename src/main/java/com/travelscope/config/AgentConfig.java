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
import com.travelscope.agent.tools.RequirementTools;
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
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
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
 * 组装架构（v4，需求与路线编排收归代码）：
 * <pre>
 * 主 Agent（travel-master，HarnessAgent）
 *  ├── 工具：WeatherTool / HotelTool / AttractionTool / TransportTool
 *  │         + TaskTools / RequirementTools + MCP（12306 火车票 / 飞常准机票）
 *  ├── Middleware：ObservationMiddleware → IntentRouterMiddleware → ReviewerRetryMiddleware
 *  └── subagentFactory: planning-agent（工具调用与行程组装）
 *        └── subagent: reviewer-agent（5 维质检）
 * 在线及隔离评测共用构建逻辑；评测另建独立 Master/Planner 实例。
 * </pre>
 * </p>
 * <p>
 * planning-agent 经 subagentFactory 工厂手工构建的原因：声明式 SubagentDeclaration
 * 注册的子 Agent 会被框架标记为叶子节点（toolkit 无 agent_spawn，无法再委派），
 * 而 Planner 需要再送审 reviewer（质检闭环），故走公开 API subagentFactory(name,
 * description, factory) 在工厂内手工构建——工厂返回的 Agent 不做叶子标记，
 * build 时自带 SubagentsMiddleware。
 * spawn 深度 master(0) → planner(1) → reviewer(2) ≤ 框架上限 3，合法。
 * </p>
 * <p>
 * 协作流程（v4，S2.5 起编排收归代码）：
 * <ol>
 *   <li>ChatService 意图路由（应用层）</li>
 *   <li>PLANNING：需求采集 workflow（清单卡片）→ intake_result.json</li>
 *   <li>TaskBacklogPlanner 代码模板拆分并登记 task_backlog.json</li>
 *   <li>ChatService 直持 planning-agent：直调工具 + CollabTools 触发代码服务
 *       （POI 池/路线求解）+ 组装 itinerary_draft.json + reviewer 质检按段回炉</li>
 *   <li>ChatService 读 itinerary_draft.json + 评审产物渲染返回用户</li>
 * </ol>
 * </p>
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
     * 注册天气查询、酒店搜索、景点搜索、市内交通等本地工具，
     * 任务容器工具（TaskTools）与需求状态机工具（RequirementTools），
     * 并通过 MCP 客户端接入火车票（12306）与飞机票（飞常准）实时查询工具，
     * 供主 Agent 和各子 Agent（SHARED 工作区、共享 Toolkit）共同使用。
     * </p>
     */
    @Bean
    public Toolkit travelToolkit(TaskRegistry taskRegistry, TripRequirementStore tripRequirementStore,
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
        // 需求状态机工具（v3 FR-S02）：intake-agent 判缺项/写回用，判断环节零模型调用
        toolkit.registerTool(new RequirementTools(tripRequirementStore));
        // RAG 双路检索 + 任务结果缓存工具（FR-S06/S11/S14）：
        // search_pois_with_rag（poi-research 双路召回）+ get/register_task_result（planner 缓存复用）
        toolkit.registerTool(new com.travelscope.agent.tools.PoiRagTools(ragServiceImpl, taskResultCache, tracingHelper));
        // 协作契约三件套（S2.6）：retrieve_poi_candidates / plan_route / write_collab_file——
        // planning-agent 触发代码服务与落盘契约文件的唯一入口
        toolkit.registerTool(collabTools);
        registerMcpClients(toolkit);
        log.info("Toolkit 注册完成: 天气/酒店/景点/交通 + 任务容器 + 需求状态机 + RAG双路/任务缓存 + 协作契约三件套 + MCP(12306、飞常准)");
        return toolkit;
    }

    /**
     * 行程需求状态仓库 Bean（intake-agent 的状态机存储，userId:sessionId 双键隔离）
     * <p>
     * Redis hash {@code trip:req:{userId}:{sessionId}}（TTL 24h 滚动刷新），
     * Redis 不可用时自动降级进程内存（见 TripRequirementStore）。
     * </p>
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
    // S2.5：itineraryPlanningAgent 同为 HarnessAgent Bean，@Primary 保按类型注入的下游
    // （ChatService.travelMasterAgent）解析到 master
    @Bean
    @org.springframework.context.annotation.Primary
    public HarnessAgent travelMasterAgent(Toolkit toolkit, Model gatewayChatModel,
                                          @org.springframework.beans.factory.annotation.Qualifier("gatewayReviewerModel") Model gatewayReviewerModel,
                                          TaskWorkspace taskWorkspaceService,
                                          io.agentscope.core.state.AgentStateStore travelAgentStateStore,
                                          TaskRegistry taskRegistry,
                                          com.travelscope.service.TaskResultCache taskResultCache) throws IOException {
        return buildMasterAgent(toolkit, gatewayChatModel, gatewayReviewerModel,
                taskWorkspaceService, travelAgentStateStore, taskRegistry, taskResultCache);
    }

    @Bean
    @org.springframework.context.annotation.Scope("prototype")
    public HarnessAgent assessmentMasterAgent(Toolkit toolkit,
            @org.springframework.beans.factory.annotation.Qualifier("gatewayChatModel") Model mainModel,
            @org.springframework.beans.factory.annotation.Qualifier("gatewayReviewerModel") Model reviewerModel,
            TaskWorkspace workspace,
            TaskRegistry taskRegistry,
            com.travelscope.service.TaskResultCache taskResultCache) throws IOException {
        return buildMasterAgent(toolkit.copy(), mainModel, reviewerModel,
                workspace, new InMemoryAgentStateStore(), taskRegistry, taskResultCache);
    }

    private HarnessAgent buildMasterAgent(Toolkit toolkit,Model gatewayChatModel,Model gatewayReviewerModel,
            TaskWorkspace taskWorkspaceService,
            io.agentscope.core.state.AgentStateStore travelAgentStateStore,
            TaskRegistry taskRegistry,
            com.travelscope.service.TaskResultCache taskResultCache) throws IOException {
        HarnessAgent agent = HarnessAgent.builder()
                .name(TravelMasterAgent.AGENT_NAME)
                .sysPrompt(TravelMasterAgent.SYS_PROMPT)
                .model(gatewayChatModel)
                // 子代理声明的字符串模型 ID 统一解析到同一模型实例
                .modelResolver(name -> gatewayChatModel)
                .toolkit(toolkit)
                .maxIters(TravelMasterAgent.MAX_ITERS)
                // 挂起的工具调用自动补错误结果继续（2026-09-21）：长规划链路被泳道超时/客户端断开/
                // 同会话并发打断后，会话状态残留 pending tool_use（无 result）——默认 false 时该会话
                // 后续每条消息都抛 IllegalStateException（Pending tool calls exist without results），
                // 会话永久卡死。开启后框架自动为 pending 调用生成错误结果，对话可继续。
                .enablePendingToolRecovery(true)
                // 责任链（S2.10 清理后）：意图路由（onSystemPrompt 注入本轮路由指令——
                // 非规划轮禁派）+ Reviewer 回炉保险丝（拦 reviewer 第 2+ 次修订改写为 hint，
                // R4 上限 1）；
                // 提示词掩码（规划按需段已删）与委派门禁（PLANNING 已代码编排，登记由
                // ChatService step2 保证）随 S2.10 移除
                .middlewares(List.of(observationMiddleware, new IntentRouterMiddleware(),
                        new ReviewerRetryMiddleware(taskWorkspaceService, tracingHelper)))
                // 本助手全部为只读查询工具 + 工作区 MD 文件协作，BYPASS 免确认，
                // 否则工具调用会挂起等待用户确认导致对话提前结束
                .permissionContext(PermissionContextState.builder()
                        .mode(PermissionMode.BYPASS)
                        .build())
                .workspace(appProperties.getAgentscope().getWorkspacePath())
                .skillRepository(new ClasspathSkillRepository("skills"))
                // 会话状态存储（2026-09-23 并发改造）：RedisAgentStateStore（Bean 由
                // RedisStateStoreConfig 提供，Redis 不可用自动降级 InMemory）——2000 用户下
                // 状态不占应用堆 + 进程重启不失忆；替代原先直接 new InMemoryAgentStateStore()
                .stateStore(travelAgentStateStore)
                // 唯一子 Agent：planning-agent（subagentFactory 工厂手工构建非叶子编排者，
                // 工厂内只挂 reviewer 质检声明，见 buildPlannerAgent；
                // 框架每次 spawn 时调用工厂 build 新实例，与 IntentClassifier 每次新建同款模式；
                // master 兜底路径不带中间件——回炉保险丝经 master 自身中间件链生效，List.of()）
                // （S2.9：intake-agent 职能已迁 RequirementWorkflowService，声明移除）
                .subagentFactory(ItineraryAgent.AGENT_NAME,
                        "规划 Agent（行程规划师）：直调工具获取天气/酒店/车票实时数据，"
                                + "经 retrieve_poi_candidates / plan_route 代码服务取候选池与分日路线，"
                                + "组装行程草案并经 reviewer-agent 质检闭环",
                        name -> buildPlannerAgent(toolkit, gatewayChatModel, gatewayReviewerModel,
                                List.of(), taskRegistry, taskResultCache, taskWorkspaceService))
                .build();

        log.info("主 Agent 构建完成: {} (workspace={}, 子 Agent: {} 工厂式[内含 {}])",
                TravelMasterAgent.AGENT_NAME,
                appProperties.getAgentscope().getWorkspacePath(),
                ItineraryAgent.AGENT_NAME, ReviewerAgent.AGENT_NAME);
        return agent;
    }

    /**
     * 手工构建规划 Agent（planning-agent，非叶子编排者）
     * <p>
     * 不走声明式 SubagentDeclaration（其子 Agent 被框架标记为叶子、无 agent_spawn），
     * 而是直接用 HarnessAgent.builder() 构建：build 时自动安装 SubagentsMiddleware，
     * 注册 agent_spawn / agent_send / agent_list / task_* 工具，因此本 Agent 能 spawn
     * 自己声明的子 Agent。深度 master(0) → planner(1) → reviewer(2)，在框架上限 3 之内。
     * </p>
     * <p>
     * S2.9：poi-research / route-optimizer 两个子 Agent 声明已随职能代码化移除
     * （POI 检索走 PoiRetrievalService、路线求解走 RoutePlannerService，planner 经
     * CollabTools 三件套触发）——本 Agent 只剩 reviewer-agent 一个质检子 Agent。
     * </p>
     * <p>
     * 传入共享 toolkit 引用（HarnessAgent.build() 内部会 copy，浅拷贝共享工具实例，
     * 不污染 travelToolkit Bean，MCP npx 进程不会重复拉起）；
     * workspace / skillRepository / 权限模式与主 Agent 一致。
     * </p>
     */
    private HarnessAgent buildPlannerAgent(Toolkit toolkit, Model gatewayChatModel,
                                           Model gatewayReviewerModel,
                                           List<io.agentscope.core.middleware.MiddlewareBase> middlewares,
                                           TaskRegistry taskRegistry,
                                           com.travelscope.service.TaskResultCache taskResultCache,
                                           com.travelscope.collab.TaskWorkspace taskWorkspace) {
        // 记账代码化（延迟优化①）：update_task_status/register_task_result 不再经 LLM 调用
        // ——planner 工具事件 → 任务状态机/缓存登记副作用（非 Bean，避免与 TaskRegistry 循环依赖）
        io.agentscope.core.middleware.MiddlewareBase bookkeeping = new com.travelscope.agent.BookkeepingMiddleware(
                taskRegistry, taskResultCache, taskWorkspace);
        // 质检子 Agent（FR-S08 v3.2 分段审核）：三段顺序审 + 5 维评分量化，
        // S2.7 起产物经 write_collab_file 写 review_passed.json / review_report.json。
        // 模型经 modelResolver 按 name 分流到质检评分模型（glm-5-3-260814，独立配置）；
        // temperature=0.1 降温（质检评分需要稳定性；agentscope 2.0.3 SubagentDeclaration
        // 原生支持 per-agent 温度，v3 未利用）；
        // tools 白名单限定（卡 A：砍外部核验工具——天气/通勤/地理全部去掉，
        // 质检只做需求符合性纯文本判断；保留契约落盘 + 缓存登记）
        SubagentDeclaration reviewer = SubagentDeclaration.builder()
                .name(ReviewerAgent.AGENT_NAME)
                .description("质量审阅 Agent（需求符合性检查员，评分模型独立配置）：比对 itinerary_draft.json 与"
                        + " intake_result.json 的需求（出发地/目的地/天数/预算/风格，各段 pass|fail），"
                        + "以 5 维评分（完备性/可行性/时间冲突/费用预算/偏好匹配，各 20 分）作量化依据，"
                        + "总分≥80 且无单维<12 且三段全 pass 才通过；全程不调外部数据工具（纯文本符合性判断），"
                        + "通过写 review_passed.json，不通过写 review_report.json"
                        + "（含分段结论与改进建议，供 planner 按段回炉）")
                .inlineAgentsBody(ReviewerAgent.SYS_PROMPT)
                .model(appProperties.getModelGateway().getReviewerModel())
                .temperature(0.1)
                .maxIters(ReviewerAgent.MAX_ITERS)
                .workspaceMode(WorkspaceMode.SHARED)
                .tools(List.of(
                        // 评审/收尾：契约落盘 + 缓存
                        "write_collab_file",
                        "register_task_result",
                        "get_cached_task_result",
                        // 卡 B 回炉修订工具（仅修订阶段用；评审阶段不调外部数据工具）
                        "plan_route",
                        "searchHotels",
                        "retrieve_poi_candidates",
                        // 卡 E POI 重选决策代码化（reviewer 只给加/删名，代码补字段+重排+重组装）
                        "revise_poi_selection",
                        "assemble_itinerary",
                        // 保险丝超限改写目标（对齐 REVIEWER_TOOL_WHITELIST，缺失会把拦截变未知工具错误）
                        "review_retry_hint"))
                .build();

        try {
            var plannerMiddlewares = new java.util.ArrayList<io.agentscope.core.middleware.MiddlewareBase>();
            plannerMiddlewares.add(observationMiddleware);
            plannerMiddlewares.add(bookkeeping);
            plannerMiddlewares.addAll(middlewares);
            HarnessAgent.Builder builder = HarnessAgent.builder()
                    .name(ItineraryAgent.AGENT_NAME)
                    .middlewares(plannerMiddlewares)
                    .sysPrompt(ItineraryAgent.SYS_PROMPT)
                    .model(gatewayChatModel)
                    // 模型分流（FR-S08）：reviewer-agent 解析到质检评分模型（独立配置），
                    // 其余（planner 自身）走主链路装饰模型——原一刀切 resolver 会把
                    // 声明里的模型名无视掉，评分模型必须在这里分流才生效
                    .modelResolver(name -> ReviewerAgent.AGENT_NAME.equals(name)
                            ? gatewayReviewerModel : gatewayChatModel)
                    // S2.11 工具面收紧：共享 toolkit 的白名单视图（copy+剪枝，不影响共享实例）
                    .toolkit(plannerToolkitView(toolkit))
                    .maxIters(ItineraryAgent.MAX_ITERS)
                    // 挂起工具调用自动补错误结果（与 master 同款，防链路打断后 spawn 卡死）
                    .enablePendingToolRecovery(true)
                    .permissionContext(PermissionContextState.builder()
                            .mode(PermissionMode.BYPASS)
                            .build())
                    .workspace(appProperties.getAgentscope().getWorkspacePath())
                    .skillRepository(new ClasspathSkillRepository("skills"))
                    .stateStore(new InMemoryAgentStateStore())
                    .subagents(List.of(reviewer));
            return builder.build();
        } catch (IOException e) {
            // subagentFactory 的 Function 不允许抛受检异常，转非受检
            throw new IllegalStateException("构建 planning-agent 失败: " + e.getMessage(), e);
        }
    }

    /**
     * S2.5 PLANNING 编排直持的行程规划 Agent（planning-agent）
     * <p>
     * 与 master 的 subagentFactory 同源构建，但显式挂 {@link ReviewerRetryMiddleware}——
     * 编排路径（S2.5 起）下本 Agent 由 ChatService 直接调起（自身即根 Agent），master
     * 中间件链覆盖不到，回炉保险丝（首审 + ≤1 次修订，R4）必须自带；master 兜底路径的工厂
     * 实例不传中间件（保险丝经 master 链生效），两条路径互不干扰。
     * review_retry_hint 工具在共享 toolkit 中已注册（TaskTools），可直接解析。
     * </p>
     */
    @Bean
    public HarnessAgent itineraryPlanningAgent(Toolkit toolkit, Model gatewayChatModel,
                                               @org.springframework.beans.factory.annotation.Qualifier("gatewayReviewerModel") Model gatewayReviewerModel,
                                               TaskWorkspace taskWorkspaceService,
                                               TaskRegistry taskRegistry,
                                               com.travelscope.service.TaskResultCache taskResultCache) {
        HarnessAgent agent = buildPlannerAgent(toolkit, gatewayChatModel, gatewayReviewerModel,
                List.of(new ReviewerRetryMiddleware(taskWorkspaceService, tracingHelper)),
                taskRegistry, taskResultCache, taskWorkspaceService);
        revokeOffScriptTools(agent);
        log.info("Agent 拓扑终态（v4 P2 收口）：{} → {} → {}（spawn 深度 3 层内）；"
                        + "planning 工具面 = 白名单 {} 项 + MCP(mcp__*) 放行 + agent_spawn/task_* 内置",
                TravelMasterAgent.AGENT_NAME, ItineraryAgent.AGENT_NAME, ReviewerAgent.AGENT_NAME,
                PLANNER_TOOL_WHITELIST.size());
        return agent;
    }

    /**
     * 框架默认脱稿工具收编（后置剪枝，探针实证 2026-10-20：read_file/write_file/edit_file/
     * web_search/web_fetch 等由框架挂在 delegate toolkit，白名单视图管不到）。
     * 规划/质检类 Agent 的产物只能经 write_collab_file / assemble_itinerary 落盘（Schema 校验
     * +原子写+观测捕获），数据只能走白名单查询工具——edit_file/write_file 是绕过契约的
     * 脱稿口子（conv-52 四轮实测 planner 手编 route_plan.json），web_search/web_fetch 是
     * 无来源标注的幻觉源，一并下线。read_file 保留（委派缺内联节的回退路径）。
     * master 保留全部（v3 兜底链手写产物需要 edit）。
     */
    public static void revokeOffScriptTools(HarnessAgent agent) {
        Toolkit delegate = agent.getDelegate().getToolkit();
        for (String name : new String[]{"edit_file", "write_file", "web_search", "web_fetch",
                "execute"}) {
            delegate.removeTool(name);
        }
    }

    /** 每次评测执行使用独立规划实例，不与在线规划共享 Agent 状态。 */
    @Bean
    @org.springframework.context.annotation.Scope("prototype")
    public HarnessAgent assessmentPlanningAgent(Toolkit toolkit,
            @org.springframework.beans.factory.annotation.Qualifier("gatewayChatModel") Model mainModel,
            @org.springframework.beans.factory.annotation.Qualifier("gatewayReviewerModel") Model reviewerModel,
            TaskWorkspace workspace,
            TaskRegistry taskRegistry,
            com.travelscope.service.TaskResultCache taskResultCache) {
        // 评测与在线同一构建口径：脱稿工具一并收编
        HarnessAgent agent = buildPlannerAgent(toolkit.copy(), mainModel, reviewerModel,
                List.of(new ReviewerRetryMiddleware(workspace, tracingHelper)),
                taskRegistry, taskResultCache, workspace);
        revokeOffScriptTools(agent);
        return agent;
    }

    /**
     * 独立质检 Agent（质检规则化的 LLM 兜底）：规则质检（RuleReviewService）未通过时
     * 由编排层直接调起做定向修订——不再是 planner 的子 Agent spawn，而是独立叶子 Agent。
     * 原型作用域：每次调用新建实例（不共享状态，同 assessmentPlanningAgent 模式）；
     * 挂 ReviewerRetryMiddleware（修订轮次保险丝拦 revise_poi_selection ≤1 次，R4）。
     */
    @Bean
    @org.springframework.context.annotation.Scope("prototype")
    public HarnessAgent standaloneReviewerAgent(Toolkit toolkit,
            @org.springframework.beans.factory.annotation.Qualifier("gatewayReviewerModel") Model gatewayReviewerModel,
            TaskWorkspace taskWorkspace) {
        Toolkit view = toolkit.copy();
        for (String name : new java.util.ArrayList<>(view.getToolNames())) {
            if (!REVIEWER_TOOL_WHITELIST.contains(name) && !name.startsWith("mcp__")) {
                view.removeTool(name);
            }
        }
        HarnessAgent agent = HarnessAgent.builder()
                .name(ReviewerAgent.AGENT_NAME)
                .sysPrompt(ReviewerAgent.SYS_PROMPT)
                .model(gatewayReviewerModel)
                .toolkit(view)
                .maxIters(ReviewerAgent.MAX_ITERS)
                .middlewares(List.of(observationMiddleware,
                        new ReviewerRetryMiddleware(taskWorkspace, tracingHelper)))
                .enablePendingToolRecovery(true)
                .permissionContext(PermissionContextState.builder()
                        .mode(PermissionMode.BYPASS).build())
                .workspace(appProperties.getAgentscope().getWorkspacePath())
                .stateStore(new InMemoryAgentStateStore())
                .build();
        revokeOffScriptTools(agent);
        return agent;
    }

    /** 独立质检 Agent 的工具白名单（与 buildPlannerAgent 内 reviewer 声明的工具面一致） */
    private static final java.util.Set<String> REVIEWER_TOOL_WHITELIST = java.util.Set.of(
            "write_collab_file", "register_task_result", "get_cached_task_result",
            "plan_route", "searchHotels", "retrieve_poi_candidates",
            "revise_poi_selection", "assemble_itinerary", "review_retry_hint");


    /**
     * planning-agent 工具白名单（S2.11 终态收紧）：数据查询（天气/酒店/交通地理/车票机票）
     * + CollabTools 三件套 + 任务缓存/状态回报 + review_retry_hint。
     * <p>
     * 两个隐性约束（勿破）：①{@code review_retry_hint} 必须可见——回炉保险丝
     * （ReviewerRetryMiddleware）把超限送审改写为该工具调用，在 planner 的 toolkit 解析，
     * 缺失会把保险丝变成未知工具错误；②reviewer 的声明白名单从本 toolkit 取子集，
     * 其事实核验工具（getDrivingRoute/getTransitRoute/geocode）必须含在内。
     * MCP 工具（mcp__* 前缀，12306/飞常准）不在名单但前缀放行（对照/降级源）。
     * </p>
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
            "review_retry_hint");

    /** 共享 toolkit 的 planner 视图：copy 后按白名单剪枝（mcp__* 前缀放行；agent_spawn/task_*
     *  由 SubagentsMiddleware 内置注册，不经 toolkit，不受剪枝影响） */
    private static Toolkit plannerToolkitView(Toolkit shared) {
        Toolkit view = shared.copy();
        for (String name : new java.util.ArrayList<>(view.getToolNames())) {
            if (!PLANNER_TOOL_WHITELIST.contains(name) && !name.startsWith("mcp__")) {
                view.removeTool(name);
            }
        }
        return view;
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
            @org.springframework.beans.factory.annotation.Qualifier("gatewayChatModel")
            Model gatewayChatModel) {
        IntentClassifier classifier = new IntentClassifier(gatewayChatModel);
        classifier.setObservation(observationMiddleware);
        return classifier;
    }

    /**
     * 创建意图级联路由器 Bean（FR-S01：L0 → L1 → L2 → L3，上层命中即短路）
     * <p>
     * v5-F3 起 L2 轻量分类改走主池（{@code gatewayChatModel}，glm 优先池内 failover；
     * 旧 gatewayLightModel 专用轻量档 deepseek-v4-flash-0731 随单 fallback 装饰退役）；
     * L3 以方法引用包装现有 IntentClassifier（零改动）。缓存为 RedisIntentCache
     * （Redis 不可用时静默降级为无缓存模式）。
     * </p>
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
