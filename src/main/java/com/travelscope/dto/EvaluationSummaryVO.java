package com.travelscope.dto;

/**
 * 评测看板汇总（2026-10-14 P2-M）：总数/通过率/坏例数 + 按类别与按用例两个分组聚合
 *
 * @param byCategory 按类别聚合（category 为空的存量行归「未分类」）
 * @param byCase     按用例聚合（只含 EvalCase 记录）
 */
public record EvaluationSummaryVO(long total, long passed, double passRate, long badCases,
                                  java.util.List<Group> byCategory,
                                  java.util.List<Group> byCase) {

    /** 单个分组（类别或用例）的通过率 */
    public record Group(String key, long total, long passed, double passRate) {
    }
}
