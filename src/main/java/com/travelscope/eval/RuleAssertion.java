package com.travelscope.eval;

/**
 * 单条确定性规则的断言结果（FR-S15 双轨评估的规则轨）。
 *
 * @param type    规则类型（与 {@link EvalCase.Rule#type()} 对应）
 * @param passed  是否通过（skipped=true 时无意义，恒为 false）
 * @param skipped 是否降级跳过（如 TOOL_ORDER 产物缺失时注明跳过，不计入 FAIL）
 * @param details 结果明细（缺失项/命中标记/跳过原因），供落库与失败定位
 */
public record RuleAssertion(String type, boolean passed, boolean skipped, String details) {

    public static RuleAssertion pass(String type, String details) {
        return new RuleAssertion(type, true, false, details);
    }

    public static RuleAssertion fail(String type, String details) {
        return new RuleAssertion(type, false, false, details);
    }

    public static RuleAssertion skip(String type, String details) {
        return new RuleAssertion(type, false, true, details);
    }

    /** 计入用例 FAIL 判定的结果：仅未通过且未跳过的 */
    public boolean countsAsFailure() {
        return !skipped && !passed;
    }

    /** 落库用的结果标记 */
    public String result() {
        if (skipped) {
            return "SKIP";
        }
        return passed ? "PASS" : "FAIL";
    }
}
