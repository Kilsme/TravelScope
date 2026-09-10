package com.travelscope.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

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

    @Data
    public static class DashScopeConfig {
        /** 通义千问 API Key */
        private String apiKey;
        /** 对话模型名称 */
        private String model = "qwen-plus";
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
    }
}
