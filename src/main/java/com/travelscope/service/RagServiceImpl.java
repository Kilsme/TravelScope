package com.travelscope.service;

import com.travelscope.config.AppProperties;
import com.travelscope.dto.RetrievedFragment;
import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.store.PgVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 双路检索实现（FR-S11：pgvector 语义 + ES BM25，RRF 融合）
 * <p>
 * <b>语义路（100% AgentScope 原生组件）</b>：DashScopeTextEmbedding（text-embedding-v3）
 * → PgVectorStore（document_chunks 表，COSINE kNN）→ SimpleKnowledge.retrieve。
 * <b>关键词路（自写）</b>：EsBm25Client（ik_smart BM25 match，框架核验：原生
 * ElasticsearchStore 只做 kNN 且 content index=false，BM25 必须自写）。
 * <b>融合（自写）</b>：RrfFusion（score=Σ 1/(rrfK+rank)，按 chunkId 去重）。
 * </p>
 * <p>
 * 可用性降级：语义路构造失败（PG/pgvector 不可用）或 ES 路失败时各自静默降级，
 * 至少一路可用即 isAvailable()=true；两路全挂返回 false（ChatService 的 RAG 分支自然跳过）。
 * RagConfig.mode 支持 dual / vector / fulltext 三档。
 * </p>
 * <p>
 * suppress "removal"：AgentScope 2.0.0 起整个 rag.model（Document/DocumentMetadata）
 * 标记 @Deprecated(forRemoval)，但 2.0.3 中 SimpleKnowledge/VDBStoreBase 的接口
 * 仍以这组类为入参/出参且无替代 API——升级 3.x 时需按新 RAG API 重写本类。
 * </p>
 */
@Service
@SuppressWarnings("removal")
public class RagServiceImpl implements RagService {

    private static final Logger log = LoggerFactory.getLogger(RagServiceImpl.class);

    private final AppProperties.RagConfig ragConfig;
    private final EsBm25Client esBm25Client;

    /** 语义路（null = pgvector 路不可用，双路退化为纯 BM25） */
    private final SimpleKnowledge vectorKnowledge;

    public RagServiceImpl(AppProperties appProperties, EsBm25Client esBm25Client,
                          javax.sql.DataSource dataSource,
                          @Value("${spring.datasource.username}") String dbUsername,
                          @Value("${spring.datasource.password}") String dbPassword) {
        this.ragConfig = appProperties.getRag();
        this.esBm25Client = esBm25Client;

        SimpleKnowledge knowledge = null;
        try {
            AppProperties.DashScopeConfig ds = appProperties.getDashscope();
            AppProperties.AgentScopeConfig as = appProperties.getAgentscope();
            PgVectorStore store = PgVectorStore.builder()
                    .jdbcUrl(jdbcUrlFrom(dataSource))
                    .username(dbUsername)
                    .password(dbPassword)
                    .tableName(as.getVectorStoreTable())       // document_chunks
                    .dimensions(as.getEmbeddingDimensions())    // 1024
                    .distanceType(PgVectorStore.DistanceType.COSINE)
                    .build();
            DashScopeTextEmbedding embedding = DashScopeTextEmbedding.builder()
                    .apiKey(ds.getApiKey())
                    .modelName(ds.getEmbeddingModel())          // text-embedding-v3
                    .dimensions(ds.getEmbeddingDimensions())
                    .build();
            knowledge = SimpleKnowledge.builder()
                    .embeddingModel(embedding)
                    .embeddingStore(store)
                    .build();
            log.info("RAG 语义路初始化: pgvector 表={}, dims={}, embedding={}",
                    as.getVectorStoreTable(), as.getEmbeddingDimensions(), ds.getEmbeddingModel());
        } catch (Exception e) {
            log.warn("RAG 语义路初始化失败（降级为纯 BM25 路）: {}", e.getMessage());
        }
        this.vectorKnowledge = knowledge;
    }

    @Override
    public boolean isAvailable() {
        if ("dual".equals(ragConfig.getMode())) {
            return vectorKnowledge != null || esBm25Client.isAvailable();
        }
        if ("vector".equals(ragConfig.getMode())) {
            return vectorKnowledge != null;
        }
        if ("fulltext".equals(ragConfig.getMode())) {
            return esBm25Client.isAvailable();
        }
        return false;
    }

    /**
     * 通用检索（ChatService RAG 分支用）：双路 RRF 融合后返回片段文本列表
     */
    @Override
    public List<String> retrieve(String question, int topK) {
        return dualRetrieveWithSource(question, topK).stream()
                .map(RetrievedFragment::content)
                .collect(Collectors.toList());
    }

    /**
     * 双路检索 + RRF 融合（poi-research 的 search_pois_with_rag 工具入口）
     *
     * @return 带来源标记（RAG / ES / RAG+ES）的融合排序片段
     */
    public List<RetrievedFragment> dualRetrieveWithSource(String query, int topK) {
        long start = System.nanoTime();
        List<RrfFusion.Entry> vectorRanked = List.of();
        List<RrfFusion.Entry> keywordRanked = List.of();

        // 语义路（pgvector kNN，模式 dual/vector 且可用时）
        if (vectorKnowledge != null && !"fulltext".equals(ragConfig.getMode())) {
            try {
                List<Document> docs = vectorKnowledge.retrieve(query,
                                RetrieveConfig.builder().limit(topK).build())
                        .block(java.time.Duration.ofSeconds(15));
                if (docs != null) {
                    vectorRanked = docs.stream()
                            .map(d -> new RrfFusion.Entry(chunkIdOf(d), textOf(d)))
                            .toList();
                }
            } catch (Exception e) {
                log.warn("语义路检索失败（本轮退化为关键词路）: {}", e.getMessage());
            }
        }

        // 关键词路（ES BM25，模式 dual/fulltext 且可用时）
        if (esBm25Client.isAvailable() && !"vector".equals(ragConfig.getMode())) {
            keywordRanked = esBm25Client.search(query, topK);
        }

        List<RetrievedFragment> fused = RrfFusion.fuse(vectorRanked, keywordRanked,
                ragConfig.getRrfK(), topK);
        log.info("rag_retrieve query='{}' 语义路={}条 BM25路={}条 融合={}条 latency={}ms",
                abbreviate(query), vectorRanked.size(), keywordRanked.size(), fused.size(),
                (System.nanoTime() - start) / 1_000_000);
        return fused;
    }

    /** 语义路文档 → chunk 标识（docId:chunkId，与 ES 侧拼接规则对齐，RRF 去重键） */
    private static String chunkIdOf(Document d) {
        String docId = d.getMetadata() != null ? d.getMetadata().getDocId() : null;
        String chunkId = d.getMetadata() != null ? d.getMetadata().getChunkId() : null;
        return (docId != null ? docId : d.getId()) + ":" + (chunkId != null ? chunkId : "0");
    }

    /** Document 文本内容 */
    private static String textOf(Document d) {
        if (d.getMetadata() != null && d.getMetadata().getContentText() != null) {
            return d.getMetadata().getContentText();
        }
        Object fromPayload = d.getPayloadValue("content");
        return fromPayload != null ? String.valueOf(fromPayload) : "";
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 30 ? s : s.substring(0, 30) + "…";
    }

    /** 从 Spring DataSource 提取 jdbcUrl（PgVectorStore 自管连接池） */
    private static String jdbcUrlFrom(javax.sql.DataSource ds) {
        try (java.sql.Connection c = ds.getConnection()) {
            return c.getMetaData().getURL();
        } catch (Exception e) {
            throw new IllegalStateException("无法从 DataSource 获取 jdbcUrl: " + e.getMessage(), e);
        }
    }
}
