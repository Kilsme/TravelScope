package com.travelscope.eval;

import java.nio.file.Path;

/**
 * 一次 EvalCase 真实执行的产物快照（规则断言与 Rubric 评分的统一输入）。
 *
 * @param finalReply       最后一轮的最终回复文本（PLANNING 轮即 itinerary_draft.md 全文，见 ChatService#handleComplete）
 * @param itineraryText    协作目录 itinerary_draft.md 内容；未产出为 null
 * @param poiShortlistText 协作目录 poi_shortlist.md 内容；未产出为 null
 * @param routePlanText    协作目录 route_plan.md 内容；未产出为 null
 * @param taskDir          会话协作目录（{workspace}/{userId}/tasks/conv-{会话id}），TOOL_ORDER 时间序判断用
 */
public record EvalRunArtifact(String finalReply, String itineraryText, String poiShortlistText,
                              String routePlanText, Path taskDir) {

    /** 行程方案文本：itinerary_draft.md 优先，缺失退化最终回复 */
    public String itineraryOrReply() {
        return itineraryText != null && !itineraryText.isBlank() ? itineraryText : finalReply;
    }
}
