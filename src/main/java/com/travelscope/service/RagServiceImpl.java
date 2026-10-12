package com.travelscope.service;

import com.travelscope.config.AppProperties;
import com.travelscope.dto.RetrievedFragment;
import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.store.PgVectorStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
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
 * <b>双路并行 + 通道级超时</b>（2026-10-11 P1-C）：两路提交到专用小线程池
 * （rag-retrieval-N，检索不与模型调用抢资源）并行执行——改造前语义路 .block(15s)
 * 跑完才走 ES 路（串行，最坏耗时 = 15s + ES 耗时），并行后单次检索耗时 ≤ 通道级超时
 * （rag.lane-timeout-ms，默认 15s，取两路大者）。每路独立计时，超时/失败路按空结果
 * 降级——只放弃等待、不中断池内任务（过小超时等于整路白算，任务仍在池内跑完）。
 * </p>
 * <p>
 * <b>召回扇出 / 最终条数拆分</b>（2026-10-11 P1-E）：每路召回走 {@code rag.recall-fanout}
 * （默认 15），融合后截取走 {@code rag.context-top-k}（默认 5）——修复原「一个 topK
 * 三义」；构造器启动校验漏斗单调收窄不变式（recallFanout ≥ contextTopK），违反即报错。
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

    /** 双路并行专用小线程池（检索不与模型调用抢资源；daemon，命名 rag-retrieval-N） */
    private final ExecutorService ragRetrievalExecutor = Executors.newFixedThreadPool(4, new java.util.concurrent.ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "rag-retrieval-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    public RagServiceImpl(AppProperties appProperties, EsBm25Client esBm25Client,
                          javax.sql.DataSource dataSource,
                          @Value("${spring.datasource.username}") String dbUsername,
                          @Value("${spring.datasource.password}") String dbPassword) {
        this.ragConfig = appProperties.getRag();
        this.esBm25Client = esBm25Client;
        // 启动校验（2026-10-11 P1-E，照 ragent RetrievalBudget 不变式）：漏斗单调收窄——
        // 召回扇出必须 ≥ 最终条数，违反即 Bean 创建失败（启动报错，防配置静默错配）
        if (ragConfig.getRecallFanout() < ragConfig.getContextTopK()) {
            throw new IllegalStateException(
                    "recallFanout(" + ragConfig.getRecallFanout() + ") < contextTopK("
                            + ragConfig.getContextTopK() + ")，违反漏斗单调收窄不变式（召回扇出必须 ≥ 最终条数）");
        }

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
     * <p>
     * 召回扇出 / 最终条数两段拆分（2026-10-11 P1-E）：每路召回数走配置
     * {@code rag.recall-fanout}（默认 15，想大保召回），本参数只决定融合后进上下文的
     * 最终条数——修复原「一个 topK 三义」（调扇出会误动最终条数）。
     * </p>
     *
     * @param contextTopK 融合后截取的最终条数（应 ≤ recall-fanout，漏斗单调收窄）
     * @return 带来源标记（RAG / ES / RAG+ES）的融合排序片段
     */
    public List<RetrievedFragment> dualRetrieveWithSource(String query, int contextTopK) {
        long start = System.nanoTime();
        long laneTimeoutMs = ragConfig.getLaneTimeoutMs();
        int recallFanout = ragConfig.getRecallFanout();

        // 双路并行（P1-C）：各自提交专用池，通道级超时内等待——改造前串行
        // （语义路 15s 跑完才走 ES），并行后单次检索耗时取两路大者
        CompletableFuture<List<RrfFusion.Entry>> vectorFuture = CompletableFuture.supplyAsync(() -> {
            // 语义路（pgvector kNN，模式 dual/vector 且可用时；召回扇出走配置，P1-E）
            if (vectorKnowledge == null || "fulltext".equals(ragConfig.getMode())) {
                return List.<RrfFusion.Entry>of();
            }
            try {
                List<Document> docs = vectorKnowledge.retrieve(query,
                                RetrieveConfig.builder().limit(recallFanout).build())
                        .block(java.time.Duration.ofMillis(laneTimeoutMs));
                return docs == null ? List.<RrfFusion.Entry>of()
                        : docs.stream().map(d -> new RrfFusion.Entry(chunkIdOf(d), textOf(d))).toList();
            } catch (Exception e) {
                log.warn("语义路检索失败（本轮退化为关键词路）: {}", e.getMessage());
                return List.of();
            }
        }, ragRetrievalExecutor);

        CompletableFuture<List<RrfFusion.Entry>> esFuture = CompletableFuture.supplyAsync(() -> {
            // 关键词路（ES BM25，模式 dual/fulltext 且可用时；召回扇出走配置，P1-E）
            if (!esBm25Client.isAvailable() || "vector".equals(ragConfig.getMode())) {
                return List.<RrfFusion.Entry>of();
            }
            return esBm25Client.search(query, recallFanout);
        }, ragRetrievalExecutor);

        List<RrfFusion.Entry> vectorRanked = awaitLane(vectorFuture, "语义路", laneTimeoutMs);
        List<RrfFusion.Entry> keywordRanked = awaitLane(esFuture, "BM25路", laneTimeoutMs);

        List<RetrievedFragment> fused = RrfFusion.fuse(vectorRanked, keywordRanked,
                ragConfig.getRrfK(), contextTopK);
        log.info("rag_retrieve query='{}' 语义路={}条 BM25路={}条 融合={}条 latency={}ms",
                abbreviate(query), vectorRanked.size(), keywordRanked.size(), fused.size(),
                (System.nanoTime() - start) / 1_000_000);
        return fused;
    }

    /**
     * 通道级超时等待（P1-C）：超时不中断任务（慢任务在池内跑完），只放弃等待——
     * 超时/失败路按空结果降级，不拖住另一路；真实耗时见 rag_retrieve 日志 latency
     */
    private static <T> List<T> awaitLane(CompletableFuture<List<T>> future, String lane, long timeoutMs) {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("{} 超过通道级超时 {}ms，按空结果降级", lane, timeoutMs);
            return List.of();
        } catch (Exception e) {
            log.warn("{} 执行失败，按空结果降级: {}", lane, e.getMessage());
            return List.of();
        }
    }

    @PreDestroy
    void shutdownRetrievalExecutor() {
        ragRetrievalExecutor.shutdownNow();
    }

    /**
     * 知识库写入（2026-10-11 P1-L 上传文档摄入入口）：chunk 列表 → DashScope embedding
     * → pgvector（与 KnowledgeIngestRunner 同通道 SimpleKnowledge.addDocuments）。
     * chunkIdOf 拼出的 {@code docId:chunkId} 与 ES 侧同构——跨路 RRF 去重键天然对齐；
     * ES 侧写入由调用方（DocumentIngestService）经 EsBm25Client.bulkIndex 完成。
     *
     * @return 实际写入条数；vectorKnowledge 构造降级为 null 时 warn 返回 0（退化为纯 ES 路）
     */
    public int addKnowledgeChunks(List<EsBm25Client.SeedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }
        if (vectorKnowledge == null) {
            log.warn("语义路不可用，{} 条 chunk 跳过 pgvector 写入（退化为纯 ES 路）", chunks.size());
            return 0;
        }
        List<Document> documents = chunks.stream()
                .map(c -> new Document(DocumentMetadata.builder()
                        .content(TextBlock.builder().text(c.content()).build())
                        .docId(c.docId())
                        .chunkId(c.chunkSeq())
                        .payload(Map.of("title", c.title() != null ? c.title() : "",
                                "source", c.source()))
                        .build()))
                .toList();
        vectorKnowledge.addDocuments(documents).block(java.time.Duration.ofMinutes(5));
        return documents.size();
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
