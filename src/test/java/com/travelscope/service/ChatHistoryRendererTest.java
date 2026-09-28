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
 * ChatHistoryRenderer 单测（失忆修复 Fix 2 + 2026-09-29 记忆摘要增强）
 * <p>
 * 覆盖：空历史/仅本轮消息返回空串、末条（本轮）排除、N 轮窗口截取（保留最近的）、
 * 单条截断、role 中文映射；记忆摘要块在历史块之前、摘要超长截断、
 * 缺口自愈（covered 落后于窗口起点时窗口前移，封顶 2× 窗口）、旧签名委托。
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
        ChatHistoryRenderer r = new ChatHistoryRenderer(3, 400, 1500);
        assertEquals("", r.render(null));
        assertEquals("", r.render(List.of()));
        assertEquals("", r.render(List.of(msg(Message.ROLE_USER, "你好"))));
    }

    @Test
    @DisplayName("末条（本轮用户消息）被排除，只渲染之前的轮次")
    void render_excludesCurrentMessage() {
        ChatHistoryRenderer r = new ChatHistoryRenderer(3, 400, 1500);
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
        ChatHistoryRenderer r = new ChatHistoryRenderer(2, 400, 1500);
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
        ChatHistoryRenderer r = new ChatHistoryRenderer(3, 100, 1500);
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
        ChatHistoryRenderer r = new ChatHistoryRenderer(3, 400, 1500);
        String out = r.render(List.of(
                msg(Message.ROLE_ASSISTANT, ""),
                msg(Message.ROLE_USER, "你好"),
                msg(Message.ROLE_USER, "本轮")));
        assertFalse(out.contains("助手：\n"));
        assertTrue(out.contains("用户：你好"));
    }

    @Test
    @DisplayName("记忆摘要块在历史块之前，两者都渲染")
    void render_withSummary_summaryBlockBeforeHistory() {
        ChatHistoryRenderer r = new ChatHistoryRenderer(3, 400, 1500);
        String out = r.render(List.of(
                msg(Message.ROLE_USER, "从哈尔滨出发去长春"),
                msg(Message.ROLE_ASSISTANT, "好的，请问玩几天？"),
                msg(Message.ROLE_USER, "本轮")),
                "用户从哈尔滨出发，预算 5000，喜欢美食", 0);
        assertTrue(out.contains("【历史对话记忆摘要"));
        assertTrue(out.contains("用户从哈尔滨出发，预算 5000，喜欢美食"));
        assertTrue(out.contains("【对话历史"));
        assertTrue(out.indexOf("【历史对话记忆摘要") < out.indexOf("【对话历史"));
        assertTrue(out.contains("用户：从哈尔滨出发去长春"));
        assertFalse(out.contains("本轮"));
    }

    @Test
    @DisplayName("摘要超长截断到 summaryMaxChars 并标注")
    void render_summaryTruncated() {
        ChatHistoryRenderer r = new ChatHistoryRenderer(3, 400, 200);
        String out = r.render(List.of(
                msg(Message.ROLE_USER, "你好"),
                msg(Message.ROLE_USER, "本轮")),
                "记".repeat(500), 0);
        assertTrue(out.contains("…（截断）"));
        // 摘要块整体（头部 + 200 字 + 标注）与历史块头部之间留不出 500 字的空间
        int summaryStart = out.indexOf("【历史对话记忆摘要");
        int historyStart = out.indexOf("【对话历史");
        assertTrue(historyStart - summaryStart < 300);
    }

    @Test
    @DisplayName("缺口自愈：covered 落后于窗口起点时窗口前移，补上未被摘要覆盖的轮次")
    void render_gapHeal_extendsWindowToCovered() {
        ChatHistoryRenderer r = new ChatHistoryRenderer(2, 400, 1500);
        List<Message> history = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            history.add(msg(Message.ROLE_USER, "第" + i + "轮问题"));
            history.add(msg(Message.ROLE_ASSISTANT, "第" + i + "轮回答"));
        }
        history.add(msg(Message.ROLE_USER, "本轮消息"));
        // prior=8 条，2 轮窗口起点本应在 4（第3轮起）；covered=2（摘要只覆盖第1轮）
        // → 窗口前移到 2：第2轮必须可见，不允许出现摘要与窗口都覆盖不到的消息
        String out = r.render(history, "早期记忆摘要内容", 2);
        assertTrue(out.contains("早期记忆摘要内容"));
        assertFalse(out.contains("第1轮"));    // 已被摘要覆盖，不重复进历史块
        assertTrue(out.contains("第2轮问题"));  // 缺口自愈：无 heal 时会被窗口裁掉
        assertTrue(out.contains("第4轮回答"));
        assertFalse(out.contains("本轮消息"));
    }

    @Test
    @DisplayName("缺口自愈封顶：covered 严重落后时窗口最多扩到 2×，剩余缺口交给异步补折")
    void render_gapHeal_cappedAtDoubleWindow() {
        ChatHistoryRenderer r = new ChatHistoryRenderer(2, 400, 1500);
        List<Message> history = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            history.add(msg(Message.ROLE_USER, "第" + i + "轮问题"));
            history.add(msg(Message.ROLE_ASSISTANT, "第" + i + "轮回答"));
        }
        history.add(msg(Message.ROLE_USER, "本轮消息"));
        // prior=20 条，正常窗口起点 16（第7轮起）；covered=2 → 前移到 max(2, 20-8)=12
        // 封顶生效：窗口仍从第7轮起，不会无限前移吞下全部历史
        String out = r.render(history, "早期记忆摘要内容", 2);
        assertFalse(out.contains("第6轮"));
        assertTrue(out.contains("第7轮问题"));
        assertTrue(out.contains("第10轮回答"));
        assertFalse(out.contains("本轮消息"));
    }

    @Test
    @DisplayName("旧签名 render(history) 委托 render(history, null, 0)，行为一致")
    void render_delegatesOldSignature() {
        ChatHistoryRenderer r = new ChatHistoryRenderer(3, 400, 1500);
        List<Message> history = List.of(
                msg(Message.ROLE_USER, "你好"),
                msg(Message.ROLE_ASSISTANT, "在的"),
                msg(Message.ROLE_USER, "本轮"));
        assertEquals(r.render(history), r.render(history, null, 0));
    }
}
