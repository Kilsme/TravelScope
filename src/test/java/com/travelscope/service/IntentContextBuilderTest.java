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
 * IntentContextBuilder 单测（2026-09-29 失忆修复 Fix 4：意图分类上下文注入）
 * <p>
 * 覆盖：空历史返回空串、末条（本轮）排除、最后一条 assistant 反问位于末尾、
 * 轮数窗口、单条/摘要截断、空内容消息跳过、仅有摘要时输出摘要。
 * </p>
 */
class IntentContextBuilderTest {

    private static Message msg(String role, String content) {
        Message m = new Message();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    @Test
    @DisplayName("null / 空历史 / 仅 1 条（即本轮刚入库）→ 返回空串")
    void build_emptyHistory() {
        IntentContextBuilder b = new IntentContextBuilder(2, 200, 600);
        assertEquals("", b.build(null, "有摘要也没用"));
        assertEquals("", b.build(List.of(), "有摘要也没用"));
        assertEquals("", b.build(List.of(msg(Message.ROLE_USER, "长春")), "有摘要也没用"));
    }

    @Test
    @DisplayName("末条（本轮用户消息）被排除，只含之前的轮次")
    void build_excludesCurrentMessage() {
        IntentContextBuilder b = new IntentContextBuilder(2, 200, 600);
        String out = b.build(List.of(
                msg(Message.ROLE_USER, "帮我规划一个行程"),
                msg(Message.ROLE_ASSISTANT, "好的，请问您想去哪里玩？"),
                msg(Message.ROLE_USER, "长春")), null);
        assertTrue(out.contains("用户：帮我规划一个行程"));
        assertTrue(out.contains("助手：好的，请问您想去哪里玩？"));
        assertFalse(out.contains("长春"));
        assertTrue(out.contains("最近对话："));
    }

    @Test
    @DisplayName("最后一条 assistant 反问位于上下文末尾（分类模型最近邻可依赖）")
    void build_lastAssistantClarifyAtEnd() {
        IntentContextBuilder b = new IntentContextBuilder(2, 200, 600);
        String out = b.build(List.of(
                msg(Message.ROLE_USER, "第1轮问题"),
                msg(Message.ROLE_ASSISTANT, "第1轮回答"),
                msg(Message.ROLE_USER, "第2轮问题"),
                msg(Message.ROLE_ASSISTANT, "请问您想去哪里玩？"),
                msg(Message.ROLE_USER, "本轮")), "用户正在规划旅行");
        assertTrue(out.endsWith("助手：请问您想去哪里玩？"), "最后一条 assistant 应在末尾: " + out);
        assertTrue(out.contains("会话摘要：用户正在规划旅行"));
    }

    @Test
    @DisplayName("超出 N 轮窗口时只保留最近 N 轮")
    void build_windowKeepsLatestRounds() {
        IntentContextBuilder b = new IntentContextBuilder(1, 200, 600);
        List<Message> history = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            history.add(msg(Message.ROLE_USER, "第" + i + "轮问题"));
            history.add(msg(Message.ROLE_ASSISTANT, "第" + i + "轮回答"));
        }
        history.add(msg(Message.ROLE_USER, "本轮"));
        String out = b.build(history, null);
        assertFalse(out.contains("第1轮"));
        assertFalse(out.contains("第2轮"));
        assertTrue(out.contains("第3轮问题"));
        assertTrue(out.contains("第3轮回答"));
    }

    @Test
    @DisplayName("单条消息超长截断到 maxCharsPerMessage 并标注")
    void build_truncatesLongMessages() {
        IntentContextBuilder b = new IntentContextBuilder(2, 100, 600);
        String out = b.build(List.of(
                msg(Message.ROLE_USER, "长".repeat(500)),
                msg(Message.ROLE_USER, "本轮")), null);
        assertTrue(out.contains("…（截断）"));
        String body = out.substring(out.indexOf("用户："));
        assertTrue(body.length() < 200);
    }

    @Test
    @DisplayName("摘要超长截断到 summaryMaxChars 并标注")
    void build_truncatesLongSummary() {
        IntentContextBuilder b = new IntentContextBuilder(2, 200, 200);
        String out = b.build(List.of(
                msg(Message.ROLE_USER, "你好"),
                msg(Message.ROLE_USER, "本轮")), "记".repeat(500));
        assertTrue(out.contains("会话摘要："));
        assertTrue(out.contains("…（截断）"));
        int summaryStart = out.indexOf("会话摘要：");
        int dialogStart = out.indexOf("最近对话：");
        assertTrue(dialogStart - summaryStart < 300, "摘要块应被截断在 200 字量级");
    }

    @Test
    @DisplayName("内容为空的消息被跳过（不产生空行）")
    void build_skipsBlankMessages() {
        IntentContextBuilder b = new IntentContextBuilder(2, 200, 600);
        String out = b.build(List.of(
                msg(Message.ROLE_ASSISTANT, "  "),
                msg(Message.ROLE_USER, "你好"),
                msg(Message.ROLE_USER, "本轮")), null);
        assertFalse(out.contains("助手：\n"));
        assertTrue(out.contains("用户：你好"));
    }

    @Test
    @DisplayName("窗口内全是空消息但有摘要时，输出仅摘要")
    void build_summaryOnlyWhenNoRenderableMessages() {
        IntentContextBuilder b = new IntentContextBuilder(2, 200, 600);
        String out = b.build(List.of(
                msg(Message.ROLE_ASSISTANT, ""),
                msg(Message.ROLE_USER, "本轮")), "用户正在规划旅行");
        assertEquals("会话摘要：用户正在规划旅行", out);
    }
}
