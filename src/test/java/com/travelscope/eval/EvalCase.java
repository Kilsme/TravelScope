package com.travelscope.eval;

import java.util.List;
import java.util.Map;

/**
 * EvalCase 用例（FR-S15）：一段多轮用户输入 + 期望行为断言。
 *
 * <p>与 src/test/resources/evalcases/ 下的 JSON 文件一一对应（文件名主干 = caseId），由 {@link EvalCaseLoader} 加载。
 *
 * @param caseId       用例编号，格式 EC-XXX，与资源文件名主干一致
 * @param category     场景类别（5 类固定用例）
 * @param userMessages 多轮用户输入，按顺序依次发送给被测系统
 * @param expectations 期望断言（确定性规则 + rubric 门槛）
 */
public record EvalCase(String caseId, EvalCategory category, List<String> userMessages,
                       Expectations expectations) {

    /**
     * 期望断言集合。
     *
     * @param rules  确定性规则断言，至少一条
     * @param rubric 模型评分门槛
     */
    public record Expectations(List<Rule> rules, Rubric rubric) {
    }

    /**
     * 单条确定性规则。
     *
     * @param type   规则类型；CONSTRAINT_COVERED 时 params 必须含非空 keyword
     * @param desc   规则说明，可空
     * @param params 规则参数；无参时为空 Map，绝不为 null
     */
    public record Rule(RuleType type, String desc, Map<String, String> params) {

        public Rule {
            if (params == null) {
                params = Map.of();
            }
        }
    }

    /**
     * Rubric 评分门槛。
     *
     * @param minTotal 总分下限（1~100，百分制口径），低于此值用例判 FAIL
     */
    public record Rubric(int minTotal) {
    }
}
