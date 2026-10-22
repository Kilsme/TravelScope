package com.travelscope.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用配置属性类
 * <p>
 * 统一管理通义千问、高德地图、和风天气、MinIO 等第三方服务的配置。
 * 所有 API Key 通过环境变量注入（高级设置），不在代码中硬编码。
 * </p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "travelscope")
public class AppProperties {

    /** 通义千问（DashScope）配置：2026-10-05 对话模型迁移至模型网关后，仅 Embedding 使用 */
    private DashScopeConfig dashscope = new DashScopeConfig();

    /** 模型网关（OpenAI 兼容）配置：全部对话模型经此网关调用（2026-10-05 迁移） */
    private ModelGatewayConfig modelGateway = new ModelGatewayConfig();


    /** 高德地图配置 */
    private AmapConfig amap = new AmapConfig();

    /** 飞猪 flyai 配置（2026-10-17 飞猪比赛接入：CLI 子进程调用） */
    private FlyaiConfig flyai = new FlyaiConfig();

    /** 和风天气配置 */
    private WeatherConfig weather = new WeatherConfig();

    /** MinIO 配置 */
    private MinioConfig minio = new MinioConfig();

    /** AgentScope 配置 */
    private AgentScopeConfig agentscope = new AgentScopeConfig();

    /** Elasticsearch 配置 */
    private ElasticsearchConfig elasticsearch = new ElasticsearchConfig();

    /** RAG 双路检索配置 */
    private RagConfig rag = new RagConfig();

    /** 意图三层级联配置（FR-S01） */
    private IntentCascadeConfig intentCascade = new IntentCascadeConfig();

    /** 需求采集 workflow 配置（S2.1 清单卡片方案：抽取置信度门槛等） */
    private RequirementConfig requirement = new RequirementConfig();

    /** LLM Gateway 配置（FR-S09） */
    private LlmGatewayConfig llmGateway = new LlmGatewayConfig();

    /** 多模型熔断池（v5-F3，2026-10-22）：chat 双池（main/reviewer 各自独立列表）+ embedding 池 */
    private ModelPoolConfig modelPool = new ModelPoolConfig();

    /** 对话历史注入配置（多轮上下文，2026-09-21 失忆修复） */
    private ChatHistoryConfig chatHistory = new ChatHistoryConfig();

    /** 积分制度配置（2026-10-11） */
    private PointsConfig points = new PointsConfig();

    /** 过载降级配置（2026-09-23 并发改造：弹性降级优先） */
    private LoadShedConfig loadShed = new LoadShedConfig();

    /** 对话历史注入配置：把近 N 轮 messages 表历史前置到发给主 Agent 的本轮消息 */
    @Data
    public static class ChatHistoryConfig {
        /** 是否注入对话历史（false = 只发本轮消息，旧行为） */
        private boolean enabled = true;
        /** 保留最近几轮（1 轮 = 1 user + 1 assistant） */
        private int rounds = 10;
        /** 每条历史消息截断长度（字符） */
        private int maxCharsPerMessage = 400;
        /** 滚动记忆摘要（2026-09-29 失忆修复：窗口外历史折叠进 conversations.summary） */
        private SummaryConfig summary = new SummaryConfig();
    }

    /**
     * 需求采集 workflow 配置（S2.1）：RequirementExtractor 的抽取置信度门槛。
     * 低于此值的字段丢弃，交清单卡片/反问兜底——宁缺毋滥。
     */
    @Data
    public static class RequirementConfig {
        /** 抽取字段保留的最小置信度（0~1） */
        private double extractionMinConfidence = 0.7;
        /** 清单卡片开关（S2.3 最小桥接：PLANNING 轮先过需求采集 workflow；false=走 v3 intake 链路） */
        private boolean formEnabled = true;
    }

    /**
     * 记忆摘要配置（增量折叠）：每满 {@code intervalRounds} 轮，把 messages[covered..T)
     * 折叠进已有摘要（轻量模型后台异步），随滑动窗口一起注入模型——第 N 轮之前
     * 聊过什么不再对模型不可见。
     */
    @Data
    public static class SummaryConfig {
        /** 是否启用记忆摘要（false = 只注入滑动窗口，无长期记忆） */
        private boolean enabled = true;
        /** 每满多少轮折叠一次（消息数差 ≥ intervalRounds×2 触发） */
        private int intervalRounds = 10;
        /** 摘要存储/注入的最大长度（字符） */
        private int maxChars = 1500;
        /** 折叠输入时单条消息截断长度（字符） */
        private int inputMaxCharsPerMessage = 600;
        /** 折叠输入总量上限（字符）——超出时按比例收紧单条截断（存量长会话首折兜底） */
        private int inputTotalMaxChars = 30000;
        /** 摘要生成模型（模型网关轻量档 deepseek-v4-flash-0731） */
        private String model = "deepseek-v4-flash-0731";
        /** 摘要生成调用超时（秒） */
        private int timeoutSeconds = 30;
    }

    /** 过载降级配置：堆使用率 + 网关剩余并发双指标，GREEN/YELLOW/RED 三级 */
    @Data
    public static class LoadShedConfig {
        /** 是否启用（false = 只走 LlmGateway 原有准入，无降级） */
        private boolean enabled = true;
        /** YELLOW 阈值：JVM 堆使用率超过即降级（0~1） */
        private double heapYellowThreshold = 0.75;
        /** RED 阈值：JVM 堆使用率超过即全面拒绝（0~1） */
        private double heapRedThreshold = 0.90;
        /** YELLOW 阈值：网关全局剩余许可占比低于即降级（0~1） */
        private double permitsYellowRatio = 0.10;
    }

    /**
     * 模型网关（OpenAI 兼容）配置：主链路与质检模型 glm-5-3-260814、轻量模型 deepseek-v4-flash-0731
     * <p>
     * 2026-10-05 对话模型全量迁移（原 DashScope qwen-plus/qwen-max/qwen-turbo）。
     * 网关协议为标准 OpenAI chat completions（Bearer 认证）；Embedding 仍走 DashScope。
     * </p>
     */
    @Data
    public static class ModelGatewayConfig {
        /** 网关 Base URL（OpenAI 兼容，如 http://ddns.trdyun.xyz:10003/v1） */
        private String baseUrl;
        /** 网关 API Key（环境变量 media_model_gateway_api_key） */
        private String apiKey;
        /** 主链路对话模型（master/planner/intake/poi/route + L3 意图分类兜底） */
        private String model = "glm-5-3-260814";
        /** 质检评分模型（reviewer-agent + EvalCase Rubric 评分，与主链路同网关独立配置） */
        private String reviewerModel = "glm-5-3-260814";
        /**
         * GLM 系模型的思考模式（thinking）开关，false = 禁用（默认）。
         * 实测（2026-10-05 E2E）：GLM-5 系默认开启思考，单轮推理 35~133s（生成大量
         * reasoning token），planner 链路 420s 预算内连 poi-research 都未跑完；
         * thinking 禁用后单轮 ~2s。经 GenerateOptions.additionalBodyParam("thinking",
         * {"type":"enabled|disabled"}) 透传，配合 compat.glm.GLMFormatter 生效。
         */
        private boolean thinkingEnabled = false;
        /**
         * 结构化输出是否走原生 json_schema response_format（无工具时）。
         * 框架在原生路径失败时自动降级合成 generate_response 工具，一般无需关闭。
         */
        private boolean nativeStructuredOutput = true;
        /**
         * 结构化输出与工具调用并存时是否仍用原生 json_schema。
         * 已知坑（OpenAIChatModel javadoc）：部分 OpenAI 兼容后端（DeepSeek/Kimi 系）在
         * tools + response_format 并存时优先 response_format 导致跳过工具调用——表现为
         * 模型不调工具直接输出 JSON。出现时置 false（结构化调用回退合成工具路径，
         * 普通工具调用不受影响）。
         */
        private boolean nativeStructuredOutputWithTools = true;
    }

    /** 通义千问（DashScope）配置：仅 Embedding（text-embedding-v3）使用 */
    @Data
    public static class DashScopeConfig {
        /** 通义千问 API Key（仅 Embedding 调用使用） */
        private String apiKey;
        /** Embedding 模型名称 */
        private String embeddingModel = "text-embedding-v3";
        /** Embedding 维度 */
        private int embeddingDimensions = 1024;
    }

    /**
     * 多模型熔断池配置（v5-F3，2026-10-22）。
     * <p>
     * chat 双池独立列表独立熔断（main=主/规划/意图/轻量，reviewer=质检更强模型），当前
     * 两池同为 glm-5-3-260814 + Kimi-k3（同网关，仅 model id 不同）；embedding 池列表
     * 顺序即优先级。熔断口径：单次响应超时立即 OPEN；累计总调用 &gt; min-calls 且成功率
     * &lt; min-success-rate 才统计熔断；OPEN 冷却到期由探针 CAS 抢单探测，通过恢复 CLOSED。
     * </p>
     */
    @Data
    public static class ModelPoolConfig {
        private BreakerConfig breaker = new BreakerConfig();
        private ChatPoolsConfig chat = new ChatPoolsConfig();
        private EmbeddingPoolConfig embedding = new EmbeddingPoolConfig();
    }

    /** 熔断全局默认（provider 可逐项覆盖超时/冷却） */
    @Data
    public static class BreakerConfig {
        /** 统计熔断门槛：累计总调用超过该值才允许按成功率熔断 */
        private int minCalls = 60;
        /** 统计熔断门槛：成功率低于该值才熔断（0.95 = 成功率 < 95%） */
        private double minSuccessRate = 0.95;
        /** OPEN 冷却时长（秒），到期转半开由探针测试 */
        private int openWaitSeconds = 30;
        /** 探针扫描间隔（秒） */
        private int probeIntervalSeconds = 10;
        /** 首包超时（秒）——挂死快速失败 */
        private long firstPacketTimeoutSeconds = 10;
        /** 块间超时（秒）——慢生成不误杀 */
        private long chunkTimeoutSeconds = 30;
        /** embedding 单次调用超时（秒） */
        private long embeddingTimeoutSeconds = 15;
    }

    @Data
    public static class ChatPoolsConfig {
        /** 主池：travel-master/planner/意图识别 L2+L3/需求抽取/会话摘要 */
        private ChatPoolSpec main = new ChatPoolSpec();
        /** reviewer 池：质检评分（更强模型，独立列表独立熔断） */
        private ChatPoolSpec reviewer = new ChatPoolSpec();
        /** 讲解专用池（私人讲解员图片轮）：多模态模型——glm-5-3 无视觉，讲解固定走
         *  kimi-k3（网关强制开思考，慢但能看图）；主池文本链路不受图片耗时拖累 */
        private ChatPoolSpec guide = new ChatPoolSpec();
    }

    @Data
    public static class ChatPoolSpec {
        private List<ProviderSpec> providers = new ArrayList<>();
    }

    /** 池内一个 chat provider（走 OpenAI 兼容网关，本地部署模型同协议仅 base-url/key 不同） */
    @Data
    public static class ProviderSpec {
        private String id;
        private String model;
        /** 网关 Base URL（空 = 沿用 travelscope.model-gateway.base-url） */
        private String baseUrl;
        private String apiKey;
        /** 池内选路顺序，小者优先（兜底模型给大值） */
        private int priority = 100;
        /** GLM 系请求参数（GLMFormatter + thinking 透传）——非 GLM 模型（如 Kimi-k3）置 false */
        private boolean useGlmFormatter = false;
        /** 首包超时覆盖（秒，空=全局默认） */
        private Long firstPacketTimeoutSeconds;
        /** 块间超时覆盖（秒，空=全局默认） */
        private Long chunkTimeoutSeconds;
        /** OPEN 冷却覆盖（秒，空=全局默认；本地/兜底模型可给更短值尽快恢复） */
        private Integer openWaitSeconds;
        /** 原生结构化输出覆盖（空=model-gateway 默认） */
        private Boolean nativeStructuredOutput;
        /** 原生结构化输出与工具并存覆盖（Kimi/DeepSeek 系有 response_format 优先跳过工具的坑，空=默认） */
        private Boolean nativeStructuredOutputWithTools;
    }

    @Data
    public static class EmbeddingPoolConfig {
        /** 列表顺序即优先级；当前 DashScope 单项，配置留扩展位 */
        private List<EmbeddingProviderSpec> providers = new ArrayList<>();
    }

    @Data
    public static class EmbeddingProviderSpec {
        private String id;
        private String model = "text-embedding-v3";
        private String apiKey;
        private int dimensions = 1024;
        /** OPEN 冷却覆盖（秒，空=全局默认） */
        private Integer openWaitSeconds;
    }

    @Data
    public static class AmapConfig {
        /** 高德地图 Web API Key */
        private String webApiKey;
        /** 高德地图 API 基础URL */
        private String baseUrl = "https://restapi.amap.com/v3";
    }

    @Data
    public static class FlyaiConfig {
        /** 飞猪 flyai API Key（环境变量 FLYAI_API_KEY；为空走免 key 体验模式） */
        private String apiKey;
        /** 是否启用飞猪数据源（false 时双源退化为单高德/原源） */
        private boolean enabled = true;
        /** CLI 单次调用超时（秒） */
        private int timeoutSeconds = 30;
        /** npx 调用的 CLI 包名（锁版本时改为 @fly-ai/flyai-cli@x.y.z） */
        private String cliPackage = "@fly-ai/flyai-cli";
    }

    @Data
    public static class WeatherConfig {
        /** 和风天气 API Host */
        private String apiHost;
        /** 和风天气 API Key */
        private String apiKey;
    }

    @Data
    public static class MinioConfig {
        /** MinIO 服务端点 */
        private String endpoint = "http://localhost:9000";
        /** MinIO 访问密钥 */
        private String accessKey = "root";
        /** MinIO 密钥 */
        private String secretKey = "root123456";
        /** MinIO Bucket 名称 */
        private String bucketName = "travelscope-docs";
    }

    @Data
    public static class AgentScopeConfig {
        /** 工作空间路径 */
        private String workspacePath = ".agentscope/workspace";
        /** 向量存储表名 */
        private String vectorStoreTable = "document_chunks";
        /** 向量维度 */
        private int embeddingDimensions = 1024;
    }

    /**
     * Elasticsearch 配置
     * <p>用于 RAG 全文检索（BM25），与 pgvector 向量检索组成双路检索</p>
     */
    @Data
    public static class ElasticsearchConfig {
        /** ES 服务地址 */
        private String url = "http://localhost:9200";
        /** 索引名称（文档块全文索引） */
        private String indexName = "document_chunks_fulltext";
        /** 向量维度（与 pgvector 保持一致） */
        private int dimensions = 1024;
        /** 用户名（开发环境未启用安全认证时留空） */
        private String username = "";
        /** 密码（开发环境未启用安全认证时留空） */
        private String password = "";
        /** 是否禁用 SSL 验证（开发环境用） */
        private boolean disableSslVerification = false;
        /** HTTP 连接超时（毫秒，2026-10-11 P1-D）：本地/局域网 ES 3s 足够 */
        private int connectTimeoutMs = 3000;
        /** HTTP 读取超时（毫秒，P1-D 治本）：小于 RAG 通道级超时（lane-timeout-ms 15s）——
         *  HTTP 层先超时让慢查询任务以异常终结、释放检索池线程，lane 层再兜底 */
        private int readTimeoutMs = 10000;
    }

    /**
     * RAG 双路检索配置
     * <p>
     * 双路检索架构：
     * <pre>
     * 用户查询
     *     │
     *     ├────── 向量检索（pgvector kNN）  ──── 语义相似度匹配
     *     │
     *     └────── 全文检索（Elasticsearch BM25） ── 关键词精确匹配
     *                     │
     *                     ▼
     *               结果融合（RRF / 加权）
     *                     │
     *                     ▼
     *               最终 Top-K 结果
     * </pre>
     * </p>
     */
    @Data
    public static class RagConfig {
        /** 检索模式: dual-双路 / vector-仅向量 / fulltext-仅全文 */
        private String mode = "dual";
        /** 向量检索权重（0~1，双路融合时使用） */
        private double vectorWeight = 0.7;
        /** 全文检索权重（0~1，双路融合时使用） */
        private double fulltextWeight = 0.3;
        /** 融合后进上下文的最终条数（即原 topK 语义，2026-10-11 P1-E 改名——
         *  与召回扇出拆开后语义不再三义） */
        private int contextTopK = 5;
        /** 每路召回扇出（P1-E：默认 15，想大保召回）。漏斗单调收窄不变式：
         *  recallFanout ≥ contextTopK——违反时 RagServiceImpl 启动报错 */
        private int recallFanout = 15;
        /** 融合算法: rrf-Reciprocal Rank Fusion / weighted-加权分数 */
        private String fusion = "rrf";
        /** RRF 参数 k（仅当 fusion=rrf 时生效） */
        private int rrfK = 60;
        /** 双路检索通道级超时（毫秒，2026-10-11 P1-C 并行化）：每路独立计时，超时路按空结果
         *  降级——只放弃等待、不中断池内任务；15s 起步（过小等于整路白算，任务仍在池内跑完） */
        private int laneTimeoutMs = 15000;
        /** 证据门控总开关（2026-10-11 清单 4.4 完整形态）：false 时融合结果直通不门控 */
        private boolean evidenceGateEnabled = true;
        /** 证据门控绝对分阈值（余弦相似度下限，低于即丢弃）。量纲前提：PgVectorStore COSINE
         *  ——score = 1 - 余弦距离（实际值域约 [0,1]，越大越相关；字节码核验
         *  convertDistanceToScore）；切换距离类型（L2/INNER_PRODUCT）时阈值需重新标定 */
        private double evidenceGateMinScore = 0.5;
        /** 种子知识库开关（开发/检测用：启动时灌入内置攻略片段，幂等跳过已存在 doc） */
        private boolean seedEnabled = false;
    }

    /**
     * 意图三层级联配置（FR-S01：L0 会话延续 → L1 规则表 → L2 轻量分类 → L3 LLM 兜底）
     * <p>
     * L1 规则表为仓库首个 List 类型配置：yml 显式配置时以 yml 为准，
     * 未配置/为空时 IntentCascadeRouter 使用代码内置同款默认规则。
     * 规则按序首个命中即生效，规划类自然语言（如「帮我规划杭州三日游」）
     * 刻意不进 L1，交由 L2/L3 语义判定。
     * </p>
     */
    @Data
    public static class IntentCascadeConfig {
        /** 级联总开关（false 时直通 L3，保持旧行为） */
        private boolean enabled = true;
        /** L1 规则表（正则 + 意图标签，按序首个命中生效） */
        private List<L1Rule> l1Rules = new ArrayList<>();
        /** L0 会话延续开关 */
        private boolean l0Enabled = true;
        /** L0 最近意图缓存 TTL（分钟） */
        private int l0TtlMinutes = 30;
        /** L0 触发的消息长度上限（字符，短追问才查缓存） */
        private int l0MaxMessageLength = 20;
        /** L2 轻量分类开关 */
        private boolean l2Enabled = true;
        /** L2 轻量分类模型（单标签，低成本，模型网关轻量档 deepseek-v4-flash-0731） */
        private String l2Model = "deepseek-v4-flash-0731";
        /** L2 文本缓存开关 */
        private boolean l2CacheEnabled = true;
        /** L2 文本缓存 TTL（分钟） */
        private int l2CacheTtlMinutes = 60;
        /** 分类上下文注入开关（2026-09-29 失忆修复：messages + 记忆摘要构建上下文随消息送 L2/L3） */
        private boolean contextEnabled = true;
        /** 上下文取最近几轮原文（1 轮 = 1 user + 1 assistant） */
        private int contextRounds = 2;
        /** 上下文单条消息截断长度（字符） */
        private int contextMaxCharsPerMessage = 200;
        /** 上下文中记忆摘要截断长度（字符） */
        private int contextSummaryMaxChars = 600;
    }

    /** L1 单条规则：正则模式 + 意图标签（CHAT/TOOL_CALL/PLANNING/RAG） */
    @Data
    public static class L1Rule {
        /** 正则表达式（Java Pattern 语法） */
        private String pattern;
        /** 意图标签（IntentType.name()） */
        private String intent;
    }

    /**
     * LLM Gateway 配置（FR-S09：限流/快慢泳道/降级）
     * <p>
     * 对话准入层配置（ChatService 调 LlmGateway——全局/单用户并发 + 全局 QPS + 快慢泳道
     * 超时）。模型调用层的熔断/超时/failover 配置 v5-F3（2026-10-22）起迁
     * {@code travelscope.model-pool}（ModelProviderPool 池装饰器），本节不再承载。
     * </p>
     */
    @Data
    public static class LlmGatewayConfig {
        /** 准入层总开关（false 时准入直通） */
        private boolean enabled = true;
        /** 全局并发上限（同时在处理的对话数） */
        private int globalConcurrency = 50;
        /** 单用户并发上限（同一用户同时在处理的对话数；当前无认证体系，guest 即全站） */
        private int perUserConcurrency = 2;
        /** 全局准入 QPS（滑动窗口限流，每秒放行的对话数） */
        private double globalQps = 10;
        /** 准入等待超时（毫秒）：信号量在此时长内拿不到即拒绝（明确提示，不白屏） */
        private int acquireTimeoutMs = 2000;
        /** 快泳道超时（秒）：CHAT/TOOL_CALL/RAG 意图的对话级超时（25s——master 含工具调用的
         *  多轮推理链实测 5s 会误杀，25s 仍与慢泳道 60s 保持数量级差异） */
        private int fastLaneTimeoutSeconds = 25;
        /** 慢泳道超时（秒）：PLANNING 意图的对话级超时。校准史：qwen 时期 300→420
         *  （回炉全链实测 275~320s）；2026-10-05 迁移 glm-5-3-260814（thinking 禁用）
         *  + RAG 语义路降级后再上调至 600（实测 450s 仍未到质检段；600 为 spawn
         *  同步等待 clamp 上限，泳道必须 ≥ 同步等待上限）。yml 显式配置以 yml 为准 */
        private int slowLaneTimeoutSeconds = 300;
    }

    /**
     * 积分制度配置（2026-10-11）：对话按意图差异化计费，调用前原子预扣
     * （UserRepository.deductPoints 单 UPDATE 完成校验+扣减，并发不超支）。
     * v1 不退款（失败轮次成本已在模型网关发生；退款策略列为后续演进）。
     */
    @Data
    public static class PointsConfig {
        /** 普通对话消耗积分（CHAT/TOOL_CALL/RAG 及意图分类失败兜底档） */
        private int chatCost = 50;
        /** 行程规划消耗积分（PLANNING） */
        private int planningCost = 200;
        /** 新用户注册初始积分（否则注册即不可用，需管理员逐人发放） */
        private long initialBalance = 500;
    }
}
