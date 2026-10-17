package com.travelscope.eval;

/**
 * EvalCase 场景类别（FR-S15 用例分类）。
 */
public enum EvalCategory {
    NORMAL_PLANNING, MISSING_INFORMATION, CLOSED_VENUE, WEATHER_CONFLICT, TOOL_FAILURE, MODIFIED_REQUIREMENTS,
    /** 多城市串联 */
    MULTI_CITY,
    /** 亲子出行 */
    FAMILY,
    /** 严格预算约束 */
    STRICT_BUDGET,
    /** 地点不存在（纠错） */
    POI_NOT_EXIST,
    /** 路线过密（时间冲突） */
    ROUTE_TOO_DENSE,
    /** 回炉恢复（2026-10-14 P2-M）：审核 fail → 按段回炉 → 最终通过审核的恢复链路 */
    REWORK_RECOVERY
}
