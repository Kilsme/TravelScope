package com.travelscope.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * SSE 对话事件载荷
 * <p>
 * 事件类型（SSE event name 与 type 一致）：
 * <ul>
 *   <li>intent - 意图分类结果，payload 为 JSON（intent/reason）</li>
 *   <li>delta  - 助手回复增量文本，payload 为文本片段</li>
 *   <li>tool   - 工具调用状态，payload 为工具名（或 工具名:结果状态）</li>
 *   <li>clarify_question - intake-agent 的反问（经 ask_user 工具），payload 为反问文本（v3 FR-S02）</li>
 *   <li>review_report - 质检报告（v3 FR-S08，payload 为 review_passed.md/review_report.md
 *       全文 markdown，前端以「📋 质检报告」折叠区渲染 5 维明细）</li>
 *   <li>done   - 回复结束，payload 为完整回复文本</li>
 *   <li>error  - 错误，payload 为错误信息（含 FR-S09 LLM Gateway 降级文案：准入拒绝/
 *               泳道超时/熔断兜底，前端以 ⚠️ 提示展示，不白屏）</li>
 * </ul>
 * </p>
 */
@Getter
@Setter
public class ChatEvent {

    public static final String TYPE_INTENT = "intent";
    public static final String TYPE_DELTA = "delta";
    public static final String TYPE_TOOL = "tool";
    public static final String TYPE_CLARIFY_QUESTION = "clarify_question";
    public static final String TYPE_REVIEW_REPORT = "review_report";
    public static final String TYPE_DONE = "done";
    public static final String TYPE_ERROR = "error";

    private String type;
    private String payload;

    public ChatEvent() {
    }

    public ChatEvent(String type, String payload) {
        this.type = type;
        this.payload = payload;
    }

    public static ChatEvent of(String type, String payload) {
        return new ChatEvent(type, payload);
    }
}
