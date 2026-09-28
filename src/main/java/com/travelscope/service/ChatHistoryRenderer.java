package com.travelscope.service;

import com.travelscope.entity.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 对话历史渲染器（多轮上下文注入，2026-09-21 失忆修复 Fix 2；2026-09-29 记忆摘要增强）
 * <p>
 * 把 messages 表的近 N 轮历史格式化为文本块，前置到发给主 Agent 的本轮消息——
 * 修复「主 Agent 只见本轮一条消息」导致的上下文缺失（如用户答「长春」两字时
 * master 完全不知道之前聊过哈尔滨出发）。对全部意图生效：CHAT 能带上下文回答、
 * PLANNING 委派指令有前文可依；同时天然兜底后端重启后框架 InMemory 记忆丢失的场景。
 * </p>
 * <p>
 * 2026-09-29 记忆摘要增强：窗口从 3 轮扩到 10 轮仍装不下整个会话，窗口外的更早轮次
 * 由 {@link ConversationSummaryService} 每满 10 轮增量折叠进 conversations.summary，
 * 本渲染器把「记忆摘要块 + 滑动窗口块」一起注入——第 N 轮之前聊过什么不再对模型不可见。
 * 纯函数无状态，可单测。截断策略：只保留最近 {@code rounds} 轮（1 轮 = 1 user + 1 assistant），
 * 每条消息截断到 {@code maxCharsPerMessage}（超长回复只留前缀，避免历史挤占上下文窗口）。
 * </p>
 */
public class ChatHistoryRenderer {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryRenderer.class);

    private final int rounds;
    private final int maxCharsPerMessage;
    private final int summaryMaxChars;

    public ChatHistoryRenderer(int rounds, int maxCharsPerMessage, int summaryMaxChars) {
        this.rounds = Math.max(0, rounds);
        this.maxCharsPerMessage = Math.max(50, maxCharsPerMessage);
        this.summaryMaxChars = Math.max(200, summaryMaxChars);
    }

    /**
     * 渲染历史块（无摘要，纯滑动窗口）
     *
     * @param history 按时间升序的会话消息（含本轮刚入库的用户消息，由本方法排除）
     * @return 历史文本块；无有效历史返回空串
     */
    public String render(List<Message> history) {
        return render(history, null, 0);
    }

    /**
     * 渲染记忆摘要 + 滑动窗口历史块（不含本轮消息——本轮消息由调用方拼接在后）
     * <p>
     * 缺口自愈：摘要覆盖进度（coveredMessages）落后于窗口起点时（折叠失败或进行中），
     * 窗口前移补上缺口——摘要与窗口之间不允许有模型看不到的消息；总量封顶 2× 窗口
     * 防极端膨胀，剩余缺口由调用方触发的异步补折在下一轮修复。
     * </p>
     *
     * @param history         按时间升序的会话消息（含本轮刚入库的用户消息，由本方法排除）
     * @param summary         记忆摘要（conversations.summary，null/空白 = 尚未生成）
     * @param coveredMessages 摘要已覆盖的消息条数（conversations.summary_covered_messages）
     * @return 文本块；无有效内容返回空串
     */
    public String render(List<Message> history, String summary, int coveredMessages) {
        if (history == null || history.size() <= 1) {
            return "";
        }
        // 末条是本轮刚入库的用户消息，排除（调用方 saveUserMessage 先行）
        List<Message> prior = history.subList(0, history.size() - 1);
        if (prior.isEmpty()) {
            return "";
        }
        // 滑动窗口起点：最近 rounds*2 条（1 轮 = user + assistant 两条；只截尾部，保证是最近的）
        int windowStart = Math.max(0, prior.size() - rounds * 2);
        boolean hasSummary = summary != null && !summary.isBlank();
        if (hasSummary && coveredMessages > 0 && coveredMessages < windowStart) {
            windowStart = Math.max(coveredMessages, prior.size() - rounds * 4);
            log.debug("摘要覆盖落后于窗口起点，窗口前移自愈: covered={}, windowStart={}",
                    coveredMessages, windowStart);
        }
        List<Message> windowed = prior.subList(windowStart, prior.size());

        StringBuilder sb = new StringBuilder();
        if (hasSummary) {
            String s = summary.trim();
            if (s.length() > summaryMaxChars) {
                s = s.substring(0, summaryMaxChars) + "…（截断）";
            }
            sb.append("【历史对话记忆摘要（更早轮次的关键信息，供长期上下文）】\n")
                    .append(s).append("\n\n");
        }
        // 轮数按实际窗口条数换算（自愈扩展时窗口可能大于 rounds 轮，避免标注与内容不符）
        int roundsShown = Math.max(1, (windowed.size() + 1) / 2);
        sb.append("【对话历史（最近 ").append(roundsShown).append(" 轮，供上下文参考，最新在最后）】\n");
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
        log.debug("历史注入: {} 条消息, 摘要={}, 块大小 {} 字符", windowed.size(), hasSummary, block.length());
        return block;
    }
}
