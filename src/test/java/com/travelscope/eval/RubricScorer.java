package com.travelscope.eval;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;

import java.time.Duration;

/**
 * Rubric 评分器（FR-S15 双轨评估的 Rubric 轨）：调 qwen-max（复用 dashscopeReviewerModel
 * Bean，即 travelscope.dashscope.reviewer-model 配置）对行程产物按三维 10 分制打分。
 * <p>
 * 调用模式复用 LightweightIntentClassifier 先例：ReActAgent 结构化输出 + 纯文本宽容解析
 * 双路（解析逻辑在 {@link RubricScores#parse} 纯函数中独立单测）；解析失败重试 1 次，
 * 仍失败抛 {@link IllegalStateException}——按 A5 翻车应急：该用例判 FAIL 并记 is_bad_case。
 * 本类只负责 LLM 调用与重试编排，不含断言逻辑。
 * </p>
 */
public class RubricScorer {

    private static final Duration SCORE_TIMEOUT = Duration.ofSeconds(120);

    /** 评分产物截断上限（qwen-max 长上下文足够，防御性截断） */
    private static final int MAX_PLAN_CHARS = 12000;

    private static final String SYS_PROMPT = """
            你是旅游行程方案的质量评审员。对给出的方案按以下三个维度打分（各 0~10 分整数，10 分最好）：
            - routeCoherence 路线连贯性：景点就近聚类、动线合理、多城市衔接顺畅
            - timeDensity 时间密度：单日安排张弛有度、通勤时长可控（单日通勤不超过 90 分钟）、无不可能完成的时刻表
            - preferenceMatch 偏好匹配：天数、城市、预算、同行人、特殊偏好（亲子/穷游/带老人等）被满足

            严格按用户需求评审：方案缺失关键内容（无预算明细、无排期、未覆盖要求的城市、忽视硬预算约束）时相应维度打低分。
            只输出 JSON，不要任何多余文字，格式：{"routeCoherence": 数字, "timeDensity": 数字, "preferenceMatch": 数字}
            """;

    private final Model reviewerModel;

    public RubricScorer(Model reviewerModel) {
        this.reviewerModel = reviewerModel;
    }

    /**
     * 对单个用例的产物打分。
     *
     * @throws IllegalStateException 无产物可评，或两次调用均无法解析出三维分数
     */
    public RubricScores score(EvalCase evalCase, EvalRunArtifact artifact) {
        String plan = artifact.itineraryOrReply();
        if (plan == null || plan.isBlank()) {
            throw new IllegalStateException("无行程产物可评分（itinerary_draft.md 与最终回复均为空）");
        }
        String prompt = "【用户需求（多轮输入）】\n" + String.join("\n", evalCase.userMessages())
                + "\n\n【行程方案】\n" + truncate(plan)
                + "\n\n请输出三维评分 JSON。";
        String lastReason = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                // 每次评分新建 Agent（IntentClassifier 同款，避免会话状态残留）
                ReActAgent judge = ReActAgent.builder()
                        .name("eval-rubric-judge")
                        .sysPrompt(SYS_PROMPT)
                        .model(reviewerModel)
                        .maxIters(2)
                        .build();
                RuntimeContext ctx = RuntimeContext.builder()
                        .userId("eval-runner")
                        .sessionId("eval-rubric-" + evalCase.caseId())
                        .build();
                Msg reply = judge.call(prompt, RubricScores.class, ctx).block(SCORE_TIMEOUT);
                if (reply != null) {
                    // 路径一：框架结构化输出（metadata 无结构化键时框架抛异常，走路径二）
                    RubricScores scores = null;
                    try {
                        scores = reply.getStructuredData(RubricScores.class);
                    } catch (Exception ignored) {
                        // qwen 系模型常见，纯文本兜底
                    }
                    if (scores != null && scores.complete()) {
                        return scores;
                    }
                    // 路径二：纯文本宽容解析（MiniJson + 正则）
                    scores = RubricScores.parse(reply.getTextContent());
                    if (scores != null) {
                        return scores;
                    }
                    lastReason = "输出无法解析: " + truncate(reply.getTextContent(), 200);
                } else {
                    lastReason = "评审模型返回为空";
                }
            } catch (Exception e) {
                lastReason = "调用失败: " + e.getMessage();
            }
        }
        throw new IllegalStateException("Rubric 评分解析失败（重试 1 次后仍失败）: " + lastReason);
    }

    private static String truncate(String text) {
        return truncate(text, MAX_PLAN_CHARS);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…(截断)";
    }
}
