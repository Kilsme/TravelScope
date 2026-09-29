package com.travelscope.service;

import com.travelscope.entity.Message;

import java.util.List;

/**
 * 意图分类上下文构建器（2026-09-29 失忆修复 Fix 4：意图分类上下文注入）
 * <p>
 * 级联的 L0 延续词正则（「长春」「三天」不含那/这个/呢/吧…）与 L2「只看当前这条
 * 消息」都判不出对 intake 反问的裸名词短回答 → 被判 CHAT 脱离规划流。本类从
 * messages 表（唯一可靠事实源）+ 记忆摘要（conversations.summary）构建紧凑上下文块，
 * 由 IntentCascadeRouter 随当前消息一起送给 L2/L3 语义判定——反问语境下裸名词判
 * PLANNING，消息自身意图明确时仍按消息本身判定。
 * </p>
 * <p>
 * 纯函数无状态，可单测。输出约 1k 字符：会话摘要（截断）+ 最近 N 轮原文（每条截断，
 * 最后一条 assistant 反问自然位于末尾）。与 {@link ChatHistoryRenderer} 同约定：
 * history 含本轮刚入库的 user 消息，由本方法排除。
 * </p>
 */
public class IntentContextBuilder {

    private final int rounds;
    private final int maxCharsPerMessage;
    private final int summaryMaxChars;

    public IntentContextBuilder(int rounds, int maxCharsPerMessage, int summaryMaxChars) {
        this.rounds = Math.max(0, rounds);
        this.maxCharsPerMessage = Math.max(50, maxCharsPerMessage);
        this.summaryMaxChars = Math.max(100, summaryMaxChars);
    }

    /**
     * 构建分类上下文块
     *
     * @param history 按时间升序的会话消息（含本轮刚入库的用户消息，由本方法排除）
     * @param summary 记忆摘要（conversations.summary，null/空白 = 尚未生成）
     * @return 上下文块；无有效内容返回空串（调用方按「无上下文」处理）
     */
    public String build(List<Message> history, String summary) {
        if (history == null || history.size() <= 1) {
            return "";
        }
        // 末条是本轮刚入库的用户消息，排除（调用方 saveUserMessage 先行）
        List<Message> prior = history.subList(0, history.size() - 1);
        if (prior.isEmpty()) {
            return "";
        }
        // 最近 rounds*2 条（1 轮 = user + assistant 两条；最后一条 assistant 反问自然在末尾）
        int window = rounds * 2;
        List<Message> windowed = window >= prior.size()
                ? prior
                : prior.subList(prior.size() - window, prior.size());

        StringBuilder sb = new StringBuilder();
        if (summary != null && !summary.isBlank()) {
            String s = summary.trim();
            if (s.length() > summaryMaxChars) {
                s = s.substring(0, summaryMaxChars) + "…（截断）";
            }
            sb.append("会话摘要：").append(s).append('\n');
        }
        StringBuilder dialog = new StringBuilder();
        for (Message m : windowed) {
            String role = Message.ROLE_USER.equals(m.getRole()) ? "用户" : "助手";
            String content = m.getContent() == null ? "" : m.getContent().trim();
            if (content.isEmpty()) {
                continue;
            }
            if (content.length() > maxCharsPerMessage) {
                content = content.substring(0, maxCharsPerMessage) + "…（截断）";
            }
            dialog.append(role).append("：").append(content).append('\n');
        }
        if (dialog.length() > 0) {
            sb.append("最近对话：\n").append(dialog);
        }
        return sb.length() == 0 ? "" : sb.toString().stripTrailing();
    }
}
