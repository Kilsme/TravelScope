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
 * 用户实体（对应 users 表）
 * <p>
 * 认证体系已落地（AuthService Redis Token）；积分制度（2026-10-11）：pointsBalance 为
 * 对话积分余额，每次对话调用前判断额度并预扣（普通对话 50 / 行程规划 200，
 * 见 PointsService）。
 * </p>
 */
@Getter
@Setter
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(length = 64)
    private String nickname;

    @Column(nullable = false, length = 20)
    private String role = "user";

    @Column(nullable = false)
    private Short status = 1;

    /** 对话积分余额（每次调用前判断额度充足并预扣；管理员发放/回收） */
    @Column(name = "points_balance", nullable = false)
    private Long pointsBalance = 0L;

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

    /** 匿名用户的固定用户名 */
    public static final String GUEST_USERNAME = "guest";
}
