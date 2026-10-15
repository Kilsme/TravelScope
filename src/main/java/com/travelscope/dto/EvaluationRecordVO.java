package com.travelscope.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 评测看板记录行（2026-10-14 P2-M）：含双轨原始 JSON（坏例诊断要看规则各项与 Rubric 各项）
 */
public record EvaluationRecordVO(Long id, String traceId, String caseId, String category,
                                 String ruleResults, String rubricScores,
                                 BigDecimal totalScore, Boolean isBadCase,
                                 LocalDateTime createdAt) {
}
