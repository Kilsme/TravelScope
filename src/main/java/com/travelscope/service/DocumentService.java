package com.travelscope.service;

import com.travelscope.entity.Document;
import com.travelscope.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 文档管理服务（FR-A03：上传 → MinIO + documents 状态机驱动）
 * <p>
 * 状态机：上传即 UPLOADED（入库）→ DocumentIngestService 真实摄入
 * （2026-10-11 P1-L：原 sleep 1s 占位线程已移除）→ PARSED / FAILED，失败可重试。
 * 文件存储在 MinIO（对象名含日期前缀与 UUID 防冲突）。
 * </p>
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final MinioService minioService;
    private final DocumentRepository documentRepository;
    private final DocumentIngestService documentIngestService;

    public DocumentService(MinioService minioService, DocumentRepository documentRepository,
                           DocumentIngestService documentIngestService) {
        this.minioService = minioService;
        this.documentRepository = documentRepository;
        this.documentIngestService = documentIngestService;
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

        // 异步真实摄入（P1-L：下载 → 解析 → 切块 → embedding → 双写，状态机 PARSED/FAILED）
        documentIngestService.parseAsync(saved.getId());
        return saved;
    }

    /**
     * 文档列表（管理端展示）
     */
    public List<Document> listAll() {
        return documentRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * 失败重试（管理端入口）：FAILED / 卡死 PARSING 的文档重新触发摄入
     */
    public void retryParse(Long documentId) {
        documentIngestService.retryParse(documentId);
    }

    private static String extOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1).toLowerCase() : "bin";
    }
}
