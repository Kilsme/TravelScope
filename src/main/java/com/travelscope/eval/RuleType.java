package com.travelscope.eval;

/**
 * EvalCase 确定性规则断言类型（FR-S15），与 runner 的纯函数断言器一一对应。
 */
public enum RuleType {
    /** 行程四维完整性：天数/每日POI/预算汇总/交通住宿天气 */
    FIELD_COMPLETE,
    /** 用户硬约束被输出覆盖（params.keyword 必填） */
    CONSTRAINT_COVERED,
    /** 不编造：地点不存在时指出并给替代建议（params.poi 必填：待核验的虚构地点名） */
    NO_HALLUCINATION,
    /** 工具调用顺序：先 POI 后路线后组装 */
    TOOL_ORDER,
    /** 路线过密时指出时间冲突并给出拆分/取舍方案（ROUTE_TOO_DENSE 专用） */
    TIME_CONFLICT_HANDLED,
    /**
     * 回炉发生且恢复（2026-10-14 P2-M，REWORK_RECOVERY 专用）：review_report.md（某次审核
     * fail 产物）与 review_passed.md（最终通过产物）同时存在。单向守护——reviewer 一次通过
     * （无 review_report.md）时降级 SKIP 而非 FAIL（没触发回炉不等于回炉坏了）
     */
    REWORK_OCCURRED,
    /**
     * 预算偏差确定性断言（2026-10-14 P2-M）：params.budget 必填（预算金额数字）、
     * params.tolerancePct 可选（默认 10）。从行程草案预算行正则提取总费用，断言
     * |实际-预算|/预算 ≤ 阈值——补齐 ±10% 纯 LLM 判定无代码层硬校验的缺口
     */
    BUDGET_DEVIATION
}
