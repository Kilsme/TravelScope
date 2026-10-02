package com.travelscope.eval;

/**
 * EvalCase 场景类别（FR-S15：5 类固定用例，对齐参考项目）。
 */
public enum EvalCategory {
    /** 多城市串联 */
    MULTI_CITY,
    /** 亲子出行 */
    FAMILY,
    /** 严格预算约束 */
    STRICT_BUDGET,
    /** 地点不存在（纠错） */
    POI_NOT_EXIST,
    /** 路线过密（时间冲突） */
    ROUTE_TOO_DENSE
}
