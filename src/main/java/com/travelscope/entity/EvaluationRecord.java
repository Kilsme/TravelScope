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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 评估记录实体（对应 evaluation_records 表，FR-S13 评估体系 / FR-S15 EvalCase 结果落库）
 * <p>
 * 规则 & Rubric 双轨评估结果的持久化：确定性规则（rule_results）与模型评分
 * （rubric_scores）各存一个 JSONB，trace_id 关联 Jaeger 全链路调用。
 * case_id 为空 = 线上真实对话评估；非空 = EvalCase 回归用例（如 EC-001）。
 * </p>
 * <p>
 * JSONB 列映射方式：String 直存 JSON 文本 + columnDefinition="jsonb"——依赖
 * datasource URL 的 stringtype=unspecified（PG 对未定型字符串参数自动转型 jsonb），
 * 读写均为 JSON 文本；不使用 @JdbcTypeCode(SqlTypes.JSON)（项目无先例，避免
 * 引入 ORM 映射魔法）。读取时 jsonb 会规范化（对象键重排），比较须按语义而非字符串。
 * </p>
 */
@Getter
@Setter
@Entity
@Table(name = "evaluation_records")
public class EvaluationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Jaeger Trace ID（关联全链路调用；tracing 未启用时可为空） */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    /** EvalCase 编号（如 EC-001；线上真实对话评估时为空） */
    @Column(name = "case_id", length = 64)
    private String caseId;

    /** 确定性规则各项通过与否（JSON 文本，JSONB 存储） */
    @Column(name = "rule_results", columnDefinition = "jsonb")
    private String ruleResults;

    /** 模型评分各项（JSON 文本，JSONB 存储） */
    @Column(name = "rubric_scores", columnDefinition = "jsonb")
    private String rubricScores;

    /** 总分（规则&Rubric 双轨合成；量纲由评估 runner 决定） */
    @Column(name = "total_score", precision = 5, scale = 2)
    private BigDecimal totalScore;

    /** 是否坏例（规则不过或 Rubric 低于阈值） */
    @Column(name = "is_bad_case", nullable = false)
    private Boolean isBadCase = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
