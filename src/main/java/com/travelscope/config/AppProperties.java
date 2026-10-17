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

    /** LLM Gateway 配置（FR-S09） */
    private LlmGatewayConfig llmGateway = new LlmGatewayConfig();

    /** 对话历史注入配置（多轮上下文，2026-09-21 失忆修复） */
    private ChatHistoryConfig chatHistory = new ChatHistoryConfig();

    /** 提示词按需加载配置（2026-10-11 P2-I） */
    private PromptMaskingConfig promptMasking = new PromptMaskingConfig();

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
     * LLM Gateway 配置（FR-S09：限流/熔断/快慢泳道/降级）
     * <p>
     * 两层职责：对话准入层（ChatService 调 LlmGateway——全局/单用户并发 + 全局 QPS +
     * 快慢泳道超时）；模型调用层（LlmGatewayModel 装饰器——熔断 + 单次超时 + fallback）。
     * </p>
     */
    @Data
    public static class LlmGatewayConfig {
        /** 网关总开关（false 时准入直通、模型不装饰，保持旧行为） */
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
        /** 模型单次调用超时（秒，LlmGatewayModel 装饰器） */
        private int modelTimeoutSeconds = 30;
        /** 首包探测超时（秒，2026-10-11 P2-G）：首个 token 的到达判据——整流 30s 超时无法
         *  区分「模型坏了」（挂死无响应）与「慢慢生成长行程」（慢但在流式输出），
         *  首包限时内未到达即判失败切 fallback（快速失败）；首包后每块间隙仍用
         *  modelTimeoutSeconds 保护。应明显小于 modelTimeoutSeconds */
        private int firstPacketTimeoutSeconds = 10;
        /** 熔断：滑动窗口大小（次） */
        private int cbSlidingWindowSize = 10;
        /** 熔断：失败率阈值（%，窗口内失败率超此值即 OPEN） */
        private int cbFailureRateThreshold = 50;
        /** 熔断：最小调用样本数（窗口内达到才计算失败率） */
        private int cbMinimumNumberOfCalls = 5;
        /** 熔断：OPEN 状态持续时间（秒），过后转 HALF_OPEN 探测 */
        private int cbWaitDurationOpenSeconds = 10;
        /** fallback 降级开关（主模型失败 → 轻量模型 deepseek-v4-flash-0731 重试一次） */
        private boolean fallbackEnabled = true;
        /** 主模型探测回切间隔（秒）：粘滞 fallback 期间定时对 primary 发最小探测调用
         *  （走熔断器，HALF_OPEN 惰性触发），成功即自动回切——替代「重启恢复」 */
        private int probeIntervalSeconds = 30;
    }

    /**
     * 提示词按需加载配置（2026-10-11 P2-I，对照 ragent AgentSkillMaskingMiddleware）
     * <p>
     * 主 Agent 的「行程规划流程 + 共享任务区间 + 规划委派规则」（~45% 篇幅）仅 PLANNING
     * 轮有意义；开启后由 PromptMaskingMiddleware 按本轮意图掩码——非规划轮只发常驻段
     * （SYS_PROMPT_CORE），控制每轮每次 ReAct 迭代的 token 成本。关闭即回退全量常驻。
     * </p>
     */
    @Data
    public static class PromptMaskingConfig {
        /** 总开关（false 时主 Agent 提示词回退为全量常驻） */
        private boolean enabled = true;
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
