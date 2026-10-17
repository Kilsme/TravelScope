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
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import com.travelscope.agent.LlmGatewayModel;
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
     * 创建主链路模型 Bean（glm-5-3-260814 @ 模型网关，经 LLM Gateway 装饰，FR-S09）
     * <p>
     * 2026-10-05 对话模型全量迁移：OpenAIChatModel 直连自建模型网关（标准 OpenAI
     * chat completions 协议 + Bearer 认证；baseUrl 以 /v1 结尾时扩展自动去重，
     * 最终请求 {base-url}/chat/completions，无需设置 endpointPath）。base-url/api-key 取自
     * travelscope.model-gateway.*（api-key 即环境变量 media_model_gateway_api_key）。
     * 外包 {@link LlmGatewayModel} 装饰器：熔断（滑动窗口失败率超阈值 → OPEN → HALF_OPEN）+
     * 单次调用超时 + 失败 fallback → deepseek-v4-flash-0731 重流。master/planner/modelResolver/
     * 4 个声明式子 Agent 全部收敛到本 Bean——包一个实例全链路生效（意图级联 L3 也经此）。
     * </p>
     * <p>
     * 返回类型为 {@link Model} 接口（装饰器模式；框架核验：Model 仅 2 抽象方法，
     * 框架自身的 fallbackModel 实现就是同款 implements Model 包装器）。
     * @Primary：按 Model 类型注入的下游解析到本装饰后主链路模型。
     * </p>
     */
    @Bean
    @org.springframework.context.annotation.Primary
    public Model gatewayChatModel(@org.springframework.beans.factory.annotation.Qualifier("gatewayLightModel") Model gatewayLightModel) {
        var mg = appProperties.getModelGateway();
        Model primary = new com.travelscope.assessment.ObservedModel(OpenAIChatModel.builder()
                .baseUrl(mg.getBaseUrl())
                .apiKey(mg.getApiKey())
                .modelName(mg.getModel())
                .stream(true)
                .formatter(new GLMFormatter())
                .generateOptions(glmDefaults(mg))
                .nativeStructuredOutput(mg.isNativeStructuredOutput())
                .nativeStructuredOutputWithTools(mg.isNativeStructuredOutputWithTools())
                .build(), observationStore);
        var gw = appProperties.getLlmGateway();
        if (!gw.isEnabled()) {
            return primary;   // 网关关闭：裸模型，保持旧行为
        }
        CircuitBreaker cb = CircuitBreaker.of("llm-gateway-model",
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(gw.getCbSlidingWindowSize())
                        .failureRateThreshold(gw.getCbFailureRateThreshold())
                        .minimumNumberOfCalls(gw.getCbMinimumNumberOfCalls())
                        .waitDurationInOpenState(Duration.ofSeconds(gw.getCbWaitDurationOpenSeconds()))
                        .build());
        return new LlmGatewayModel(primary,
                gw.isFallbackEnabled() ? gatewayLightModel : null, cb,
                Duration.ofSeconds(gw.getModelTimeoutSeconds()),
                Duration.ofSeconds(gw.getFirstPacketTimeoutSeconds()),
                gw.isFallbackEnabled(), tracingHelper).assessment(observationStore);
    }

    /**
     * LLM Gateway 对话准入层 Bean（FR-S09：全局/单用户并发 + QPS + 泳道超时）
     */
    @Bean
    public LlmGateway llmGateway() {
        return new LlmGateway(appProperties);
    }

    /**
     * L2 轻量分类模型 Bean（deepseek-v4-flash-0731 @ 模型网关，FR-S01 意图级联）
     * <p>
     * 与主链路共用网关 base-url/api-key，仅模型名不同（travelscope.intent-cascade.l2-model）；
     * 被意图级联的 L2 层使用（LightweightIntentClassifier），并作为主链路
     * LlmGatewayModel 的 fallback 目标（fallback 是降级终点，自身不再套熔断；
     * 主/备同网关——网关整体故障时 fallback 同失效，由熔断 OPEN 快速失败 + SSE error 兜底）。
     * </p>
     */
    @Bean
    public Model gatewayLightModel() {
        var mg = appProperties.getModelGateway();
        return new com.travelscope.assessment.ObservedModel(OpenAIChatModel.builder()
                .baseUrl(mg.getBaseUrl())
                .apiKey(mg.getApiKey())
                .modelName(appProperties.getIntentCascade().getL2Model())
                .stream(true)
                .nativeStructuredOutput(mg.isNativeStructuredOutput())
                .nativeStructuredOutputWithTools(mg.isNativeStructuredOutputWithTools())
                .build(), observationStore);
    }

    /**
     * 记忆摘要生成模型 Bean（deepseek-v4-flash-0731 @ 模型网关，2026-09-29 失忆修复）
     * <p>
     * ConversationSummaryService 每满 10 轮后台折叠一次会话历史的低成本低延迟辅助调用，
     * 与意图级联 L2 同级定位。独立实例而非复用轻量 Bean：模型名走
     * chat-history.summary.model 独立配置（与 l2-model 各自可调，当前同档）。
     * 裸模型不经 LlmGateway 装饰——后台任务不占对话准入槽位、不影响主链路熔断统计。
     * </p>
     */
    @Bean
    public Model conversationSummaryModel() {
        var mg = appProperties.getModelGateway();
        return new com.travelscope.assessment.ObservedModel(OpenAIChatModel.builder()
                .baseUrl(mg.getBaseUrl())
                .apiKey(mg.getApiKey())
                .modelName(appProperties.getChatHistory().getSummary().getModel())
                .stream(true)
                .nativeStructuredOutput(mg.isNativeStructuredOutput())
                .nativeStructuredOutputWithTools(mg.isNativeStructuredOutputWithTools())
                .build(), observationStore);
    }

    /**
     * 质检评分模型 Bean（glm-5-3-260814 @ 模型网关，FR-S08：reviewer-agent 专用）
     * <p>
     * 评分模型与主链路模型名独立配置（travelscope.model-gateway.reviewer-model，
     * 当前同为 glm-5-3-260814）；由 buildPlannerAgent 的 modelResolver 按 agent name
     * 分流到 reviewer-agent，EvalCase 的 Rubric 评分复用本 Bean。
     * </p>
     */
    @Bean
    public Model gatewayReviewerModel() {
        var mg = appProperties.getModelGateway();
        return new com.travelscope.assessment.ObservedModel(OpenAIChatModel.builder()
                .baseUrl(mg.getBaseUrl())
                .apiKey(mg.getApiKey())
                .modelName(mg.getReviewerModel())
                .stream(true)
                .formatter(new GLMFormatter())
                .generateOptions(glmDefaults(mg))
                .nativeStructuredOutput(mg.isNativeStructuredOutput())
                .nativeStructuredOutputWithTools(mg.isNativeStructuredOutputWithTools())
                .build(), observationStore);
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
                                          io.agentscope.core.state.AgentStateStore travelAgentStateStore) throws IOException {
        return buildMasterAgent(toolkit, gatewayChatModel, gatewayReviewerModel,
                taskWorkspaceService, travelAgentStateStore);
    }

    @Bean
    @org.springframework.context.annotation.Scope("prototype")
    public HarnessAgent assessmentMasterAgent(Toolkit toolkit,
            @org.springframework.beans.factory.annotation.Qualifier("gatewayChatModel") Model mainModel,
            @org.springframework.beans.factory.annotation.Qualifier("gatewayReviewerModel") Model reviewerModel,
            TaskWorkspace workspace) throws IOException {
        return buildMasterAgent(toolkit.copy(), mainModel, reviewerModel,
                workspace, new InMemoryAgentStateStore());
    }

    private HarnessAgent buildMasterAgent(Toolkit toolkit,Model gatewayChatModel,Model gatewayReviewerModel,
            TaskWorkspace taskWorkspaceService,
            io.agentscope.core.state.AgentStateStore travelAgentStateStore) throws IOException {
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
                // 非规划轮禁派）+ Reviewer 回炉保险丝（FR-S08：第 3+ 次送审拦截改写为 hint）；
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
                                List.of()))
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
                                           List<io.agentscope.core.middleware.MiddlewareBase> middlewares) {
        // 质检子 Agent（FR-S08 v3.2 分段审核）：三段顺序审 + 5 维评分量化，
        // S2.7 起产物经 write_collab_file 写 review_passed.json / review_report.json。
        // 模型经 modelResolver 按 name 分流到质检评分模型（glm-5-3-260814，独立配置）；
        // temperature=0.1 降温（质检评分需要稳定性；agentscope 2.0.3 SubagentDeclaration
        // 原生支持 per-agent 温度，v3 未利用）；
        // tools 白名单限定核验工具面（天气/路线/地理 + 契约落盘 + 缓存登记）
        SubagentDeclaration reviewer = SubagentDeclaration.builder()
                .name(ReviewerAgent.AGENT_NAME)
                .description("质量审阅 Agent（质检员，评分模型独立配置）：对 itinerary_draft.json 做分段审核"
                        + "（段1 POI 有效性 / 段2 路线连贯性+时间密度 / 段3 预算+偏好匹配，各段 pass|fail），"
                        + "以 5 维评分（完备性/可行性/时间冲突/费用预算/POI 合理性，各 20 分）作量化依据，"
                        + "总分≥80 且无单维<12 且三段全 pass 才通过；调工具核验事实（重查距离/天气），"
                        + "通过写 review_passed.json，不通过写 review_report.json"
                        + "（含分段结论与改进建议，供 planner 按段回炉）")
                .inlineAgentsBody(ReviewerAgent.SYS_PROMPT)
                .model(appProperties.getModelGateway().getReviewerModel())
                .temperature(0.1)
                .maxIters(ReviewerAgent.MAX_ITERS)
                .workspaceMode(WorkspaceMode.SHARED)
                .tools(List.of(
                        "getWeather",
                        "getWeatherForecast",
                        "getDrivingRoute",
                        "getTransitRoute",
                        "geocode",
                        "write_collab_file",
                        "register_task_result",
                        "get_cached_task_result"))
                .build();

        try {
            var plannerMiddlewares = new java.util.ArrayList<io.agentscope.core.middleware.MiddlewareBase>();
            plannerMiddlewares.add(observationMiddleware);
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
     * 中间件链覆盖不到，回炉保险丝（首审 + ≤2 次回炉）必须自带；master 兜底路径的工厂
     * 实例不传中间件（保险丝经 master 链生效），两条路径互不干扰。
     * review_retry_hint 工具在共享 toolkit 中已注册（TaskTools），可直接解析。
     * </p>
     */
    @Bean
    public HarnessAgent itineraryPlanningAgent(Toolkit toolkit, Model gatewayChatModel,
                                               @org.springframework.beans.factory.annotation.Qualifier("gatewayReviewerModel") Model gatewayReviewerModel,
                                               TaskWorkspace taskWorkspaceService) {
        HarnessAgent agent = buildPlannerAgent(toolkit, gatewayChatModel, gatewayReviewerModel,
                List.of(new ReviewerRetryMiddleware(taskWorkspaceService, tracingHelper)));
        log.info("Agent 拓扑终态（v4 P2 收口）：{} → {} → {}（spawn 深度 3 层内）；"
                        + "planning 工具面 = 白名单 {} 项 + MCP(mcp__*) 放行 + agent_spawn/task_* 内置",
                TravelMasterAgent.AGENT_NAME, ItineraryAgent.AGENT_NAME, ReviewerAgent.AGENT_NAME,
                PLANNER_TOOL_WHITELIST.size());
        return agent;
    }

    /** 每次评测执行使用独立规划实例，不与在线规划共享 Agent 状态。 */
    @Bean
    @org.springframework.context.annotation.Scope("prototype")
    public HarnessAgent assessmentPlanningAgent(Toolkit toolkit,
            @org.springframework.beans.factory.annotation.Qualifier("gatewayChatModel") Model mainModel,
            @org.springframework.beans.factory.annotation.Qualifier("gatewayReviewerModel") Model reviewerModel,
            TaskWorkspace workspace) {
        return buildPlannerAgent(toolkit.copy(), mainModel, reviewerModel,
                List.of(new ReviewerRetryMiddleware(workspace, tracingHelper)));
    }

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
            "retrieve_poi_candidates", "plan_route", "write_collab_file",
            "register_task_result", "get_cached_task_result",
            "update_task_status", "get_task_progress",
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
     * L2 注入 deepseek-v4-flash-0731 轻量分类器（模型网关轻量档）；L3 以方法引用包装现有
     * IntentClassifier（零改动）；缓存为 RedisIntentCache（Redis 不可用时静默降级为无缓存模式）。
     * </p>
     */
    @Bean
    public IntentCascadeRouter intentCascadeRouter(
            IntentCache intentCache,
            IntentClassifier intentClassifier,
            @org.springframework.beans.factory.annotation.Qualifier("gatewayLightModel")
            Model gatewayLightModel) {
        LightweightIntentClassifier l2 = new LightweightIntentClassifier(gatewayLightModel);
        l2.setObservation(observationMiddleware);
        return new IntentCascadeRouter(appProperties, intentCache,
                new IntentCascadeRouter.LlmClassifier() {
                    public com.travelscope.dto.IntentResult classify(String m,String c,String u,String s) {return l2.classify(m,c,u,s);}
                    public com.travelscope.dto.IntentResult classify(String m,String c,String u,String s,com.travelscope.assessment.ObservationContext o) {return l2.classify(m,c,u,s,o);}
                }, new IntentCascadeRouter.LlmClassifier() {
                    public com.travelscope.dto.IntentResult classify(String m,String c,String u,String s) {return intentClassifier.classify(m,c,u,s);}
                    public com.travelscope.dto.IntentResult classify(String m,String c,String u,String s,com.travelscope.assessment.ObservationContext o) {return intentClassifier.classify(m,c,u,s,o);}
                });
    }

}
