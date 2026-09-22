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

import java.time.LocalDateTime;

/**
 * 消息实体（对应 messages 表）
 * <p>
 * 仅映射核心列；JSONB 列（content_blocks/tool_calls/metadata）暂不映射，插入时保持 NULL。
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

    /** 消息文本内容 */
    @Column(nullable = false, columnDefinition = "text")
    private String content;

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
