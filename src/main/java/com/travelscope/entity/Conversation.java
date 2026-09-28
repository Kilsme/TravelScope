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
 * 会话实体（对应 conversations 表）
 */
@Getter
@Setter
@Entity
@Table(name = "conversations")
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 256)
    private String title = "新会话";

    /** Agent 类型（当前固定 travel_assistant） */
    @Column(name = "agent_type", nullable = false, length = 64)
    private String agentType = "travel_assistant";

    /** 会话状态：active / archived */
    @Column(nullable = false, length = 20)
    private String status = "active";

    /** 会话记忆摘要（2026-09-29 失忆修复：每满 10 轮把窗口外历史增量折叠，注入模型作长期上下文） */
    @Column(columnDefinition = "text")
    private String summary;

    /** 摘要已覆盖的 messages 条数（增量折叠进度，见 ConversationSummaryService） */
    @Column(name = "summary_covered_messages", nullable = false)
    private Integer summaryCoveredMessages = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
