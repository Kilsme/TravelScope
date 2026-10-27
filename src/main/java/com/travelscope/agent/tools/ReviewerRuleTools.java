package com.travelscope.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.travelscope.service.RuleReviewService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.LinkedHashMap;
import java.util.Map;

/** reviewer-agent 专用的确定性规则复检工具。 */
public class ReviewerRuleTools {

    public static final String TOOL_NAME = "rule_review_check";

    private final RuleReviewService ruleReviewService;

    public ReviewerRuleTools(RuleReviewService ruleReviewService) {
        this.ruleReviewService = ruleReviewService;
    }

    /**
     * 对协作目录中的当前 itinerary_draft.json 执行 Java 规则复检并写入标准 review 产物。
     * reviewer 首次进入时调用一次；发生修订并重组装草案后必须再次调用。
     */
    @Tool(name = TOOL_NAME, description = "reviewer-agent 专用：对当前 itinerary_draft.json 执行确定性规则复检。"
            + "工具会读取 intake/POI/route/draft 契约并写入 review_passed.json 或 review_report.json；"
            + "返回 verdict、reasonCode、retryable、issues、warnings 和可用时的 revisionTask。"
            + "进入评审时必须先调用；修订草案后必须再次调用，禁止自行估分或手写评审产物。")
    public String check(
            @ToolParam(name = "sessionId", description = "本轮会话 ID，必须使用主管委派说明中的值")
            String sessionId,
            RuntimeContext ctx) {
        if (sessionId == null || sessionId.isBlank() || !sessionId.equals(ctx.getSessionId())) {
            return "ERROR: sessionId 与当前运行上下文不一致";
        }
        RuleReviewService.Outcome outcome = ruleReviewService.check(ctx.getUserId(), sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("verdict", outcome.verdict().name());
        result.put("passed", outcome.passed());
        result.put("reasonCode", outcome.reasonCode());
        result.put("retryable", outcome.retryable());
        result.put("issues", outcome.issues());
        result.put("warnings", outcome.warnings());
        if (outcome.revisionTaskText() != null && !outcome.revisionTaskText().isBlank()) {
            result.put("revisionTask", outcome.revisionTaskText());
        }
        return JSON.toJSONString(result);
    }
}
