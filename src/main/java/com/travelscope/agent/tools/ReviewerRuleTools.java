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
    private final com.travelscope.collab.TaskWorkspace taskWorkspace;
    private final com.travelscope.service.review.ReviewActionRouter actionRouter =
            new com.travelscope.service.review.ReviewActionRouter();

    public ReviewerRuleTools(RuleReviewService ruleReviewService) {
        this(ruleReviewService, null);
    }

    public ReviewerRuleTools(RuleReviewService ruleReviewService,
                             com.travelscope.collab.TaskWorkspace taskWorkspace) {
        this.ruleReviewService = ruleReviewService;
        this.taskWorkspace = taskWorkspace;
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
        var initialRoute = actionRouter.route(outcome.reasonCode());
        boolean codeRecalculated = false;
        if (initialRoute.status() == com.travelscope.service.review.ReviewActionRouter.Status.CODE_RECALCULATE
                && taskWorkspace != null) {
            codeRecalculated = recalculateBudget(ctx.getUserId(), sessionId);
            if (codeRecalculated) outcome = ruleReviewService.check(ctx.getUserId(), sessionId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("codeRecalculated", codeRecalculated);
        result.put("verdict", outcome.verdict().name());
        result.put("passed", outcome.passed());
        result.put("reasonCode", outcome.reasonCode());
        result.put("retryable", outcome.retryable());
        var route = actionRouter.route(outcome.reasonCode());
        Map<String, Object> actionRoute = new LinkedHashMap<>();
        actionRoute.put("status", route.status().name());
        actionRoute.put("allowedActions", route.allowedActions());
        actionRoute.put("llmAllowed", route.llmAllowed());
        actionRoute.put("mustRecheck", route.mustRecheck());
        result.put("actionRoute", actionRoute);
        result.put("issues", outcome.issues());
        result.put("warnings", outcome.warnings());
        if (taskWorkspace != null && outcome.verdict() != RuleReviewService.Outcome.Verdict.FAIL) {
            var draft = taskWorkspace.readItineraryDraft(ctx.getUserId(), sessionId);
            if (draft.isPresent()) {
                var quality = new com.travelscope.service.review.QualityRubric().score(draft.get(),
                        taskWorkspace.readRoutePlan(ctx.getUserId(), sessionId).orElse(null),
                        taskWorkspace.readPoiShortlist(ctx.getUserId(), sessionId).orElse(null),
                        taskWorkspace.readIntakeResult(ctx.getUserId(), sessionId).orElse(null));
                result.put("qualityScore", quality);
            }
        }
        if (outcome.revisionTaskText() != null && !outcome.revisionTaskText().isBlank()) {
            result.put("revisionTask", outcome.revisionTaskText());
        }
        return JSON.toJSONString(result);
    }

    /** 确定性重算分项总额；不经过模型。 */
    private boolean recalculateBudget(String userId, String sessionId) {
        var draftOpt = taskWorkspace.readItineraryDraft(userId, sessionId);
        if (draftOpt.isEmpty() || draftOpt.get().budget() == null) return false;
        var draft = draftOpt.get();
        java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
        java.util.regex.Pattern number = java.util.regex.Pattern.compile("\\d+(?:\\.\\d{1,2})?");
        for (var item : draft.budget().breakdown()) {
            var matcher = number.matcher(item.amount());
            if (!matcher.find() || item.amount().matches(".*[~～约起].*")) return false;
            sum = sum.add(new java.math.BigDecimal(matcher.group()));
        }
        String total = "¥" + sum.stripTrailingZeros().toPlainString();
        var budget = new com.travelscope.collab.ItineraryDraft.Budget(
                draft.budget().currency(), total, draft.budget().breakdown());
        taskWorkspace.writeItineraryDraft(userId, sessionId,
                new com.travelscope.collab.ItineraryDraft(draft.days(), budget, draft.notes(),
                        draft.pendingConfirm(), draft.markdownBody()));
        return true;
    }
}
