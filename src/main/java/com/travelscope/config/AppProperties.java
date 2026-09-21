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

    /** 通义千问（DashScope）配置 */
    private DashScopeConfig dashscope = new DashScopeConfig();


    /** 高德地图配置 */
    private AmapConfig amap = new AmapConfig();

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

    /** 对话历史注入配置：把近 N 轮 messages 表历史前置到发给主 Agent 的本轮消息 */
    @Data
    public static class ChatHistoryConfig {
        /** 是否注入对话历史（false = 只发本轮消息，旧行为） */
        private boolean enabled = true;
        /** 保留最近几轮（1 轮 = 1 user + 1 assistant） */
        private int rounds = 3;
        /** 每条历史消息截断长度（字符） */
        private int maxCharsPerMessage = 400;
    }

    @Data
    public static class DashScopeConfig {
        /** 通义千问 API Key */
        private String apiKey;
        /** 对话模型名称 */
        private String model = "qwen-plus";
        /** 质检评分模型（FR-S08：reviewer-agent 专用，与主链路分离） */
        private String reviewerModel = "qwen-max";
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
        /** 返回结果数量 */
        private int topK = 5;
        /** 融合算法: rrf-Reciprocal Rank Fusion / weighted-加权分数 */
        private String fusion = "rrf";
        /** RRF 参数 k（仅当 fusion=rrf 时生效） */
        private int rrfK = 60;
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
        /** L2 轻量分类模型（单标签，低成本） */
        private String l2Model = "qwen-turbo";
        /** L2 文本缓存开关 */
        private boolean l2CacheEnabled = true;
        /** L2 文本缓存 TTL（分钟） */
        private int l2CacheTtlMinutes = 60;
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
        /** 慢泳道超时（秒）：PLANNING 意图的对话级超时 */
        /** 慢泳道超时（秒）：PLANNING 意图的对话级超时（300s——完整链路 = planner 直调工具
         *  + poi/route 子任务 + reviewer qwen-max 评分 + ≤2 次回炉，实测单段 30-60s，
         *  60s 必然切断；仍在 spawn 同步 600s 上限内） */
        private int slowLaneTimeoutSeconds = 300;
        /** 模型单次调用超时（秒，LlmGatewayModel 装饰器） */
        private int modelTimeoutSeconds = 30;
        /** 熔断：滑动窗口大小（次） */
        private int cbSlidingWindowSize = 10;
        /** 熔断：失败率阈值（%，窗口内失败率超此值即 OPEN） */
        private int cbFailureRateThreshold = 50;
        /** 熔断：最小调用样本数（窗口内达到才计算失败率） */
        private int cbMinimumNumberOfCalls = 5;
        /** 熔断：OPEN 状态持续时间（秒），过后转 HALF_OPEN 探测 */
        private int cbWaitDurationOpenSeconds = 10;
        /** fallback 降级开关（主模型失败 → qwen-turbo 重试一次） */
        private boolean fallbackEnabled = true;
    }
}
