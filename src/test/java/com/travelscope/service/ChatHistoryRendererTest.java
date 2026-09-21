package com.travelscope.service;

import com.travelscope.entity.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatHistoryRenderer 单测（失忆修复 Fix 2）
 * <p>
 * 覆盖：空历史/仅本轮消息返回空串、末条（本轮）排除、N 轮窗口截取（保留最近的）、
 * 单条截断、role 中文映射。
 * </p>
 */
class ChatHistoryRendererTest {

    private static Message msg(String role, String content) {
        Message m = new Message();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    @Test
    @DisplayName("null / 空历史 / 仅 1 条（即本轮刚入库）→ 返回空串")
    void render_emptyHistory() {
        ChatHistoryRenderer r = new ChatHistoryRenderer(3, 400);
        assertEquals("", r.render(null));
        assertEquals("", r.render(List.of()));
        assertEquals("", r.render(List.of(msg(Message.ROLE_USER, "你好"))));
    }

    @Test
    @DisplayName("末条（本轮用户消息）被排除，只渲染之前的轮次")
    void render_excludesCurrentMessage() {
        ChatHistoryRenderer r = new ChatHistoryRenderer(3, 400);
        String out = r.render(List.of(
                msg(Message.ROLE_USER, "想从哈尔滨去长春玩"),
                msg(Message.ROLE_ASSISTANT, "请问玩几天？"),
                msg(Message.ROLE_USER, "三天")));   // 本轮，应排除
        assertTrue(out.contains("用户：想从哈尔滨去长春玩"));
        assertTrue(out.contains("助手：请问玩几天？"));
        assertFalse(out.contains("三天"));
        assertTrue(out.startsWith("【对话历史"));
    }

    @Test
    @DisplayName("超出 N 轮窗口时只保留最近 N 轮（旧轮次被丢弃）")
    void render_windowKeepsLatestRounds() {
        ChatHistoryRenderer r = new ChatHistoryRenderer(2, 400);
        List<Message> history = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            history.add(msg(Message.ROLE_USER, "第" + i + "轮问题"));
            history.add(msg(Message.ROLE_ASSISTANT, "第" + i + "轮回答"));
        }
        history.add(msg(Message.ROLE_USER, "本轮消息"));
        String out = r.render(history);
        // 2 轮窗口 = 最近 4 条历史（第4、5轮）+ 排除本轮
        assertFalse(out.contains("第1轮"));
        assertFalse(out.contains("第2轮"));
        assertFalse(out.contains("第3轮"));
        assertTrue(out.contains("第4轮问题"));
        assertTrue(out.contains("第4轮回答"));
        assertTrue(out.contains("第5轮问题"));
        assertTrue(out.contains("第5轮回答"));
        assertFalse(out.contains("本轮消息"));
    }

    @Test
    @DisplayName("单条超长消息截断到 maxCharsPerMessage 并标注")
    void render_truncatesLongMessages() {
        ChatHistoryRenderer r = new ChatHistoryRenderer(3, 100);
        String longContent = "长".repeat(500);
        String out = r.render(List.of(
                msg(Message.ROLE_USER, longContent),
                msg(Message.ROLE_USER, "本轮")));
        assertTrue(out.contains("…（截断）"));
        // 截断后单条 ≤ 100 字 + 标注
        String body = out.substring(out.indexOf("用户："));
        assertTrue(body.length() < 200);
    }

    @Test
    @DisplayName("内容为空的消息被跳过（不产生空行）")
    void render_skipsBlankMessages() {
        ChatHistoryRenderer r = new ChatHistoryRenderer(3, 400);
        String out = r.render(List.of(
                msg(Message.ROLE_ASSISTANT, ""),
                msg(Message.ROLE_USER, "你好"),
                msg(Message.ROLE_USER, "本轮")));
        assertFalse(out.contains("助手：\n"));
        assertTrue(out.contains("用户：你好"));
    }
}
