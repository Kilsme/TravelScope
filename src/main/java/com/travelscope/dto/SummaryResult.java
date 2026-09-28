package com.travelscope.dto;

/**
 * 记忆摘要结构化输出载体（2026-09-29 失忆修复）
 * <p>
 * 供 AgentScope 结构化输出（{@code agent.call(msg, SummaryResult.class)}）反序列化。
 * qwen-turbo 对结构化输出遵循度不稳定（见 LightweightIntentClassifier 注释），
 * 解析失败时调用方回退取 {@code Msg.getTextContent()} 纯文本。
 * </p>
 */
public class SummaryResult {

    /** 合并后的记忆摘要全文 */
    public String summary;

    public SummaryResult() {
    }

    public SummaryResult(String summary) {
        this.summary = summary;
    }
}
