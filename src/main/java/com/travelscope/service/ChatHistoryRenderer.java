package com.travelscope.service;

import com.travelscope.entity.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 对话历史渲染器（多轮上下文注入，2026-09-21 失忆修复 Fix 2）
 * <p>
 * 把 messages 表的近 N 轮历史格式化为文本块，前置到发给主 Agent 的本轮消息——
 * 修复「主 Agent 只见本轮一条消息」导致的上下文缺失（如用户答「长春」两字时
 * master 完全不知道之前聊过哈尔滨出发）。对全部意图生效：CHAT 能带上下文回答、
 * PLANNING 委派指令有前文可依；同时天然兜底后端重启后框架 InMemory 记忆丢失的场景。
 * </p>
 * <p>
 * 纯函数无状态，可单测。截断策略：只保留最近 {@code rounds} 轮（1 轮 = 1 user + 1 assistant），
 * 每条消息截断到 {@code maxCharsPerMessage}（超长回复只留前缀，避免历史挤占上下文窗口）。
 * </p>
 */
public class ChatHistoryRenderer {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryRenderer.class);

    private final int rounds;
    private final int maxCharsPerMessage;

    public ChatHistoryRenderer(int rounds, int maxCharsPerMessage) {
        this.rounds = Math.max(0, rounds);
        this.maxCharsPerMessage = Math.max(50, maxCharsPerMessage);
    }

    /**
     * 渲染历史块（不含本轮消息——本轮消息由调用方拼接在后）
     *
     * @param history 按时间升序的会话消息（含本轮刚入库的用户消息，由本方法排除）
     * @return 历史文本块；无有效历史返回空串
     */
    public String render(List<Message> history) {
        if (history == null || history.size() <= 1) {
            return "";
        }
        // 末条是本轮刚入库的用户消息，排除（调用方 saveUserMessage 先行）
        List<Message> prior = history.subList(0, history.size() - 1);
        if (prior.isEmpty()) {
            return "";
        }
        // 只保留最近 rounds*2 条（1 轮 = user + assistant 两条；只截尾部，保证是最近的）
        int window = rounds * 2;
        List<Message> windowed = window >= prior.size()
                ? prior
                : prior.subList(prior.size() - window, prior.size());

        StringBuilder sb = new StringBuilder();
        sb.append("【对话历史（最近 ").append(rounds).append(" 轮，供上下文参考，最新在最后）】\n");
        for (Message m : windowed) {
            String role = Message.ROLE_USER.equals(m.getRole()) ? "用户" : "助手";
            String content = m.getContent() == null ? "" : m.getContent().trim();
            if (content.isEmpty()) {
                continue;
            }
            if (content.length() > maxCharsPerMessage) {
                content = content.substring(0, maxCharsPerMessage) + "…（截断）";
            }
            sb.append(role).append("：").append(content).append('\n');
        }
        String block = sb.toString();
        log.debug("历史注入: {} 条消息, 块大小 {} 字符", windowed.size(), block.length());
        return block;
    }
}
