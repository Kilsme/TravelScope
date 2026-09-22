package com.travelscope.service;

import com.travelscope.entity.Document;
import com.travelscope.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 文档管理服务（FR-A03：上传 → MinIO + documents 状态机 UPLOADED→PARSING 占位）
 * <p>
 * 状态机：上传即 UPLOADED（入库）→ 异步流转 PARSING（占位，真实解析由 RAG 摄入接）。
 * 文件存储在 MinIO（对象名含日期前缀与 UUID 防冲突）。
 * </p>
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final MinioService minioService;
    private final DocumentRepository documentRepository;

    public DocumentService(MinioService minioService, DocumentRepository documentRepository) {
        this.minioService = minioService;
        this.documentRepository = documentRepository;
    }

    /**
     * 上传文档：写 MinIO + 入库（status=UPLOADED）+ 异步流转 PARSING 占位
     *
     * @param file 上传的文件（MultipartFile）
     * @return 已入库的 Document（含 id）
     */
    public Document upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        String originalName = file.getOriginalFilename() != null
                ? file.getOriginalFilename() : "unnamed";
        String fileType = extOf(originalName);
        String objectName = "docs/" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "/" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                + "-" + originalName;

        try (InputStream in = file.getInputStream()) {
            minioService.upload(objectName, in, file.getSize(), file.getContentType());
        } catch (Exception e) {
            throw new IllegalStateException("文件上传失败: " + e.getMessage(), e);
        }

        Document doc = new Document();
        doc.setTitle(originalName);
        doc.setFileName(originalName);
        doc.setFileType(fileType);
        doc.setFileSize(file.getSize());
        doc.setFilePath(objectName);
        doc.setStatus(Document.STATUS_UPLOADED);
        Document saved = documentRepository.save(doc);
        log.info("文档已上传入库: id={} file={} size={} path={}",
                saved.getId(), originalName, file.getSize(), objectName);

        // 异步流转 PARSING（占位状态机；真实解析在 RAG 摄入时接）
        triggerParsing(saved.getId());
        return saved;
    }

    /**
     * 文档列表（管理端展示）
     */
    public List<Document> listAll() {
        return documentRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * 触发解析（异步占位：1s 后流转 UPLOADED → PARSING；真实解析在 RAG 摄入时接）
     * <p>
     * 用线程池异步而非 @Async 自调用（@Async 同类自调用不走代理不生效）。
     * </p>
     */
    private void triggerParsing(Long documentId) {
        new Thread(() -> {
            try {
                Thread.sleep(1000);
                documentRepository.findById(documentId).ifPresent(d -> {
                    if (Document.STATUS_UPLOADED.equals(d.getStatus())) {
                        d.setStatus(Document.STATUS_PARSING);
                        documentRepository.save(d);
                        log.info("文档状态流转 UPLOADED → PARSING（占位）: id={}", documentId);
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "doc-parsing-placeholder-" + documentId).start();
    }

    private static String extOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1).toLowerCase() : "bin";
    }
}
