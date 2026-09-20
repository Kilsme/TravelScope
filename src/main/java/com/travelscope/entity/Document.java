package com.travelscope.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 文档实体（对应 documents 表，FR-A03 知识文档上传）
 * <p>
 * 状态机：UPLOADED → PARSING → PARSED / FAILED（VARCHAR 存储，DDL 注释四态；
 * 上传即 UPLOADED，异步占位流转到 PARSING，真实解析由 RAG 摄入逻辑接）。
 * </p>
 */
@Getter
@Setter
@Entity
@Table(name = "documents")
public class Document {

    /** 状态：UPLOADED / PARSING / PARSED / FAILED */
    public static final String STATUS_UPLOADED = "UPLOADED";
    public static final String STATUS_PARSING = "PARSING";
    public static final String STATUS_PARSED = "PARSED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 文档标题 */
    @Column(nullable = false, length = 512)
    private String title;

    /** 原始文件名 */
    @Column(name = "file_name", nullable = false, length = 512)
    private String fileName;

    /** 文件类型（pdf / docx / txt / md / html） */
    @Column(name = "file_type", nullable = false, length = 20)
    private String fileType;

    /** 文件大小（字节） */
    @Column(name = "file_size")
    private Long fileSize;

    /** MinIO 存储路径（对象名） */
    @Column(name = "file_path", nullable = false, length = 1024)
    private String filePath;

    /** 访问 URL（可选） */
    @Column(name = "file_url", length = 1024)
    private String fileUrl;

    /** 全文内容（解析后回填，大文档不入库可留空） */
    @Column(columnDefinition = "text")
    private String content;

    /** 切块数量（解析完成后回填） */
    @Column(name = "chunk_count")
    private Integer chunkCount;

    /** 状态（UPLOADED / PARSING / PARSED / FAILED） */
    @Column(nullable = false, length = 20)
    private String status = STATUS_UPLOADED;

    /** 错误信息（FAILED 时） */
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = STATUS_UPLOADED;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
