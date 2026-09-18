package com.travelscope.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.travelscope.config.AppProperties;
import com.travelscope.service.RrfFusion.Entry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch BM25 关键词检索客户端（FR-S11 双路检索的 ES 路）
 * <p>
 * 框架核验结论：AgentScope 原生 ElasticsearchStore 只做 kNN（content 字段 index=false），
 * 无 BM25 能力——本类为自写实现，用 pom 已有的 elasticsearch-java 9.0.2（Rest5Client）。
 * 索引 mapping 用仓库预置的 index-mapping.json（content: ik_max_word 索引 / ik_smart 搜索）。
 * </p>
 * <p>
 * 降级：连接/查询失败静默降级（首次 warn 后续 debug），{@link #isAvailable()}=false 时
 * 不参与双路融合——与 RedisIntentCache 同款容错风格。
 * </p>
 */
@Service
public class EsBm25Client {

    private static final Logger log = LoggerFactory.getLogger(EsBm25Client.class);

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AppProperties.ElasticsearchConfig config;
    private final Rest5Client restClient;
    private final ElasticsearchClient client;

    /** 可用性缓存：构造成功 + 最近一次操作未持续失败 */
    private volatile boolean available;

    private volatile boolean failureLogged = false;

    public EsBm25Client(AppProperties appProperties) {
        this.config = appProperties.getElasticsearch();
        Rest5Client rc = null;
        ElasticsearchClient ec = null;
        try {
            // 低层 HTTP 客户端（开发环境 xpack.security.enabled=false 匿名访问）
            rc = Rest5Client.builder(URI.create(config.getUrl())).build();
            ec = new ElasticsearchClient(new Rest5ClientTransport(rc, new JacksonJsonpMapper()));
        } catch (Exception e) {
            logFailure("构造 Rest5Client", e);
        }
        this.restClient = rc;
        this.client = ec;
        this.available = rc != null && ec != null;
        if (available) {
            log.info("ES BM25 客户端初始化: url={}, index={}", config.getUrl(), config.getIndexName());
        }
    }

    /** ES 路是否可用（不可用时双路退化为纯语义路） */
    public boolean isAvailable() {
        return available && client != null;
    }

    /**
     * 幂等确保索引存在（不存在则按 index-mapping.json 创建）
     */
    public void ensureIndex() {
        if (!isAvailable()) {
            return;
        }
        try {
            boolean exists = client.indices()
                    .exists(e -> e.index(config.getIndexName())).value();
            if (exists) {
                return;
            }
            try (InputStream is = getClass().getResourceAsStream("/elasticsearch/index-mapping.json")) {
                if (is == null) {
                    log.warn("index-mapping.json 未找到（classpath:elasticsearch/），跳过建索引");
                    return;
                }
                client.indices().create(c -> c.index(config.getIndexName()).withJson(is));
                log.info("ES 索引已创建: {}（ik_max_word/ik_smart + dense_vector 1024）", config.getIndexName());
            }
        } catch (Exception e) {
            logFailure("ensureIndex", e);
        }
    }

    /**
     * BM25 关键词检索（content 字段 match，ik_smart 搜索分词）
     *
     * @return 按 _score 降序的条目（chunkId 用 doc_id+chunk_id 拼接，与 pgvector 侧对齐去重）
     */
    public List<Entry> search(String query, int topK) {
        if (!isAvailable() || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            Query matchQuery = Query.of(q -> q
                    .multiMatch(m -> m
                            .fields("content", "title")
                            .query(query)));
            SearchResponse<Map> response = client.search(s -> s
                            .index(config.getIndexName())
                            .query(matchQuery)
                            .size(Math.max(1, topK)),
                    Map.class);
            List<Entry> entries = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                Map<String, Object> src = hit.source();
                if (src == null) {
                    continue;
                }
                String content = String.valueOf(src.getOrDefault("content", ""));
                String docId = String.valueOf(src.getOrDefault("doc_id", ""));
                Object chunkId = src.get("chunk_id");
                entries.add(new Entry(docId + ":" + chunkId, content));
            }
            return entries;
        } catch (Exception e) {
            logFailure("search", e);
            return List.of();
        }
    }

    /**
     * 批量写入 chunk（种子数据/文档摄取用）：content 可检索（BM25），embedding 字段跳过（向量只在 pgvector）
     */
    public boolean bulkIndex(List<SeedChunk> chunks) {
        if (!isAvailable() || chunks == null || chunks.isEmpty()) {
            return false;
        }
        try {
            ensureIndex();
            BulkRequest.Builder bulk = new BulkRequest.Builder();
            for (SeedChunk c : chunks) {
                Map<String, Object> doc = new HashMap<>();
                doc.put("doc_id", c.docId());
                doc.put("chunk_id", c.chunkSeq());
                doc.put("title", c.title());
                doc.put("content", c.content());
                doc.put("file_type", "seed");
                doc.put("source", c.source());
                doc.put("created_at", LocalDateTime.now().format(FMT));
                bulk.operations(BulkOperation.of(o -> o
                        .index(idx -> idx
                                .index(config.getIndexName())
                                .id(c.docId() + ":" + c.chunkSeq())
                                .document(doc))));
            }
            BulkResponse resp = client.bulk(bulk.build());
            if (resp.errors()) {
                log.warn("ES bulk 写入存在失败项: {} 条", resp.items().size());
                return false;
            }
            log.info("ES bulk 写入成功: {} 条 chunk → 索引 {}", chunks.size(), config.getIndexName());
            return true;
        } catch (Exception e) {
            logFailure("bulkIndex", e);
            return false;
        }
    }

    /** 刷新索引（写入后立即可见，种子场景用） */
    public void refresh() {
        if (!isAvailable()) {
            return;
        }
        try {
            client.indices().refresh(r -> r.index(config.getIndexName()));
        } catch (IOException e) {
            logFailure("refresh", e);
        }
    }

    /** 关闭底层传输 */
    public void close() {
        if (restClient != null) {
            try {
                restClient.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 种子/摄取的单条 chunk（ES 侧文档） */
    public record SeedChunk(String docId, String chunkSeq, String title, String content, String source) {
    }

    private void logFailure(String operation, Exception e) {
        if (failureLogged) {
            log.debug("ES 操作失败（已降级，双路退化为语义路）: op={}, 原因: {}", operation, e.getMessage());
        } else {
            failureLogged = true;
            this.available = false;
            log.warn("ES 首次操作失败，BM25 路降级关闭（双路退化为语义路）: op={}, 原因: {}",
                    operation, e.getMessage());
        }
    }
}
