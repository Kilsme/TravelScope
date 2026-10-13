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
 * 邀请码实体（对应 invitation_codes 表，2026-10-11 注册准入）
 * <p>
 * 新用户仅凭有效（存在且未使用）邀请码注册；管理员经管理端发放。
 * used_by/used_at 为 NULL 即未使用。
 * </p>
 */
@Getter
@Setter
@Entity
@Table(name = "invitation_codes")
public class InvitationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 邀请码（唯一） */
    @Column(nullable = false, unique = true, length = 32)
    private String code;

    /** 发放人（管理员用户 ID） */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /** 使用人（注册成功的用户 ID；未使用为 null） */
    @Column(name = "used_by")
    private Long usedBy;

    /** 使用时间 */
    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
