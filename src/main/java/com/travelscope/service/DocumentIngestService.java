package com.travelscope.service;

import com.travelscope.entity.Document;
import com.travelscope.repository.DocumentRepository;
import jakarta.annotation.PreDestroy;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 文档摄入服务（2026-10-11 P1-L：占位状态机 → 真实 ETL）
 * <p>
 * 管线（对照 ragent ETL 六节点，起步只取必要四节点）：MinIO 下载原件 → 文本抽取
 * （pdf/docx/html 走 Apache Tika，md/txt 按 UTF-8 直读）→ 定长滑窗切块（800 字/重叠 100，
 * 结构感知切块为后续演进）→ DashScope embedding → 双写 pgvector + ES（复用
 * {@link RagServiceImpl#addKnowledgeChunks} 与 {@link EsBm25Client#bulkIndex} 现有通道）。
 * </p>
 * <p>
 * 状态机由本服务驱动：PARSING（任务开始）→ PARSED（成功，回填 chunkCount）/
 * FAILED（异常，回填 errorMessage），失败可经 {@link #retryParse} 重试。
 * <b>重试安全</b>：pgvector 行 id 是框架 UUID、无幂等 upsert——重写前按 doc_id 清理旧块；
 * ES 侧文档 id 定址（doc-{id}:{seq}）天然覆盖。
 * </p>
 */
@Service
public class DocumentIngestService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestService.class);

    /** 切块大小（字符，固定长度起步——结构感知切块后续演进） */
    static final int CHUNK_SIZE = 800;
    /** 相邻块重叠（字符，保上下文衔接） */
    static final int CHUNK_OVERLAP = 100;
    /** errorMessage 截断上限 */
    private static final int MAX_ERROR_LEN = 500;

    private final MinioService minioService;
    private final DocumentRepository documentRepository;
    private final RagServiceImpl ragService;
    private final EsBm25Client esBm25Client;
    private final javax.sql.DataSource dataSource;

    /** 摄入专用小线程池（重 IO + embedding，不占对话泳道与检索池；daemon 命名 doc-ingest-N） */
    private final ExecutorService ingestExecutor = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "doc-ingest-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    public DocumentIngestService(MinioService minioService, DocumentRepository documentRepository,
                                 RagServiceImpl ragService, EsBm25Client esBm25Client,
                                 javax.sql.DataSource dataSource) {
        this.minioService = minioService;
        this.documentRepository = documentRepository;
        this.ragService = ragService;
        this.esBm25Client = esBm25Client;
        this.dataSource = dataSource;
    }

    /**
     * 异步摄入（DocumentService.upload 上传即触发）
     */
    public void parseAsync(Long documentId) {
        ingestExecutor.submit(() -> ingest(documentId));
    }

    /**
     * 失败重试（管理端入口）：FAILED / 卡死 PARSING 状态均可重发摄入任务
     */
    public void retryParse(Long documentId) {
        log.info("文档摄入重试触发: id={}", documentId);
        parseAsync(documentId);
    }

    /** 摄入主流程：状态机 PARSING → PARSED / FAILED */
    private void ingest(Long documentId) {
        documentRepository.findById(documentId).ifPresentOrElse(doc -> {
            doc.setStatus(Document.STATUS_PARSING);
            doc.setErrorMessage(null);
            documentRepository.save(doc);
            log.info("文档摄入开始: id={} file={} type={}",
                    documentId, doc.getFileName(), doc.getFileType());
            try {
                int chunkCount = doIngest(doc);
                doc.setStatus(Document.STATUS_PARSED);
                doc.setChunkCount(chunkCount);
                doc.setErrorMessage(null);
                documentRepository.save(doc);
                log.info("文档摄入完成: id={} chunks={}", documentId, chunkCount);
            } catch (Exception e) {
                log.error("文档摄入失败: id={} file={}", documentId, doc.getFileName(), e);
                doc.setStatus(Document.STATUS_FAILED);
                doc.setErrorMessage(abbreviate(e));
                documentRepository.save(doc);
            }
        }, () -> log.warn("摄入任务找不到文档（可能已删除）: id={}", documentId));
    }

    /** 管线本体：下载 → 解析 → 切块 → 双写（抛异常由 ingest 统一转 FAILED） */
    private int doIngest(Document doc) throws Exception {
        String text;
        try (InputStream in = minioService.download(doc.getFilePath())) {
            text = extractText(in, doc.getFileType());
        }
        List<String> pieces = chunk(text);
        if (pieces.isEmpty()) {
            throw new IllegalStateException("解析结果无有效文本块（空文档或抽取失败）");
        }

        String docId = "doc-" + doc.getId();
        deleteVectorChunks(docId);   // 重试安全：pgvector 无幂等 upsert，先清旧块
        List<EsBm25Client.SeedChunk> chunks = new ArrayList<>();
        for (int i = 0; i < pieces.size(); i++) {
            chunks.add(new EsBm25Client.SeedChunk(docId, String.valueOf(i + 1),
                    doc.getTitle(), pieces.get(i), "upload", doc.getFileType()));
        }
        esBm25Client.bulkIndex(chunks);
        esBm25Client.refresh();
        int vectorWritten = ragService.addKnowledgeChunks(chunks);
        log.info("文档双写完成: id={} es={} 条 vector={} 条", doc.getId(), chunks.size(), vectorWritten);
        return chunks.size();
    }

    /** 文本抽取：md/txt 直读（UTF-8），pdf/docx/html 走 Tika 自动侦测 */
    private static String extractText(InputStream in, String fileType) throws Exception {
        if ("md".equalsIgnoreCase(fileType) || "txt".equalsIgnoreCase(fileType)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(-1);   // -1 = 不限长度
        parser.parse(in, handler, new Metadata(), new ParseContext());
        return handler.toString();
    }

    /**
     * 定长滑窗切块（package-private 便于单测）：CHUNK_SIZE 字/块、CHUNK_OVERLAP 字重叠，
     * 跳过纯空白块；整段空白输入返回空列表
     */
    static List<String> chunk(String text) {
        List<String> pieces = new ArrayList<>();
        if (text == null) {
            return pieces;
        }
        String normalized = text.replace("\r\n", "\n").trim();
        int len = normalized.length();
        if (len == 0) {
            return pieces;
        }
        int step = CHUNK_SIZE - CHUNK_OVERLAP;
        for (int start = 0; start < len; start += step) {
            int end = Math.min(start + CHUNK_SIZE, len);
            String piece = normalized.substring(start, end).trim();
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
            if (end >= len) {
                break;
            }
        }
        return pieces;
    }

    /** 重试安全：按 doc_id 清理 pgvector 旧块（失败仅告警——继续摄入可能产生重复块，可接受） */
    private void deleteVectorChunks(String docId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM document_chunks WHERE doc_id = ?")) {
            ps.setString(1, docId);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                log.info("清理旧向量块（重试/重传）: docId={} 删除={} 条", docId, deleted);
            }
        } catch (Exception e) {
            log.warn("清理旧向量块失败（继续摄入，可能产生重复块）: docId={} - {}", docId, e.getMessage());
        }
    }

    private static String abbreviate(Exception e) {
        String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
        return msg.length() <= MAX_ERROR_LEN ? msg : msg.substring(0, MAX_ERROR_LEN);
    }

    @PreDestroy
    void shutdownIngestExecutor() {
        ingestExecutor.shutdownNow();
    }
}
