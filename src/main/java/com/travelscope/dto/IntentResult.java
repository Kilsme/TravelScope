package com.travelscope.dto;

import com.travelscope.agent.IntentType;

/**
 * 意图分类结果（结构化输出用 POJO）
 * <p>
 * 供 AgentScope 结构化输出（{@code agent.call(msg, IntentResult.class)}）反序列化，
 * 字段使用 String 接收模型输出，再经 {@link #toIntentType()} 安全归一化为枚举，
 * 避免模型输出非法枚举值导致解析失败。
 * </p>
 */
public class IntentResult {

    /** 意图类别（模型输出的原始字符串，取值应为 CHAT/TOOL_CALL/PLANNING/RAG）。
     * 声明在 reason 之前（intent-first）：2026-10-22 v7 实测 reason 先行时 glm 把 JSON
     * 当纯文本输出会写完 reason 就收尾（reason-only JSON 漏掉 intent），标签先行对
     * 输出截断/早停天然免疫——截断时标签已在前部。 */
    public String intent;

    /** 分类理由（一句简短判定依据，含指代解析结论，用于日志与调试） */
    public String reason;

    public IntentResult() {
    }

    public IntentResult(String intent, String reason) {
        this.intent = intent;
        this.reason = reason;
    }

    /**
     * 归一化为意图枚举
     *
     * @return 识别出的意图；无法识别时返回 null（由调用方回退为主 Agent 自主决策）
     */
    public IntentType toIntentType() {
        if (intent == null) {
            return null;
        }
        try {
            return IntentType.valueOf(intent.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
