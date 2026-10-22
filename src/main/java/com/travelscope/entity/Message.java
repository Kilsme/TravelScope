package com.travelscope.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 消息实体（对应 messages 表）
 * <p>
 * content_blocks 已映射（JSONB，私人讲解员图片引用：
 * {@code {"images":[{"mediaType":"image/jpeg","objectKey":"chat-images/..."}]}}——
 * 存 MinIO 对象引用而非 base64 本体）；tool_calls/metadata 仍不映射，插入保持 NULL。
 * </p>
 */
@Getter
@Setter
@Entity
@Table(name = "messages")
public class Message {

    /** 消息角色 */
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 角色：user / assistant */
    @Column(nullable = false, length = 20)
    private String role;

    /** 消息文本内容（图片轮为占位文本「[图片] {问题}」，base64 绝不进此列） */
    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** 多模态内容块引用（JSONB；null = 纯文本消息） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_blocks", columnDefinition = "jsonb")
    private String contentBlocks;

    @Column(name = "model_name", length = 128)
    private String modelName;

    /** 消息消耗 token 数（FR-A01 Token 用量聚合数据源；user 消息计输入，assistant 计主链输出+意图分类） */
    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.tokenCount == null) {
            this.tokenCount = 0;
        }
    }
}
