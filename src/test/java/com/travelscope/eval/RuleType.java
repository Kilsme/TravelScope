package com.travelscope.eval;

/**
 * EvalCase 确定性规则断言类型（FR-S15），与 runner 的纯函数断言器一一对应。
 */
public enum RuleType {
    /** 行程四维完整性：天数/每日POI/预算汇总/交通住宿天气 */
    FIELD_COMPLETE,
    /** 用户硬约束被输出覆盖（params.keyword 必填） */
    CONSTRAINT_COVERED,
    /** 不编造：地点不存在时指出并给替代建议 */
    NO_HALLUCINATION,
    /** 工具调用顺序：先 POI 后路线后组装 */
    TOOL_ORDER,
    /** 路线过密时指出时间冲突并给出拆分/取舍方案（ROUTE_TOO_DENSE 专用） */
    TIME_CONFLICT_HANDLED
}
