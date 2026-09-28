package com.travelscope.service;

import com.travelscope.config.AppProperties;
import com.travelscope.entity.Conversation;
import com.travelscope.entity.Message;
import com.travelscope.repository.ConversationRepository;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ConversationSummaryService 单测（2026-09-29 失忆修复：滚动记忆摘要）
 * <p>
 * 覆盖：到期判定边界（每满 10 轮）、折叠输入切片与自适应截断、提示词组装、
 * 触发预检（enabled / 到期 / inFlight 防重 / 会话缺失）、空内容只推进进度不调模型、
 * doFold 结束后释放 inFlight 占位。模型调用本身（qwen-turbo 结构化 + 纯文本双路）
 * 与 LightweightIntentClassifier 同构，不在本单测范围内。
 * </p>
 */
class ConversationSummaryServiceTest {

    private ConversationService conversationService;
    private ConversationRepository conversationRepository;
    private Model summaryModel;
    private AppProperties appProperties;
    private ConversationSummaryService service;

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        conversationRepository = mock(ConversationRepository.class);
        summaryModel = mock(Model.class);
        appProperties = new AppProperties();
        service = new ConversationSummaryService(
                conversationService, conversationRepository, summaryModel, appProperties);
    }

    private static Message msg(String role, String content) {
        Message m = new Message();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    /** n 轮完整对话（user + assistant 成对，共 2n 条） */
    private static List<Message> rounds(int n) {
        List<Message> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(msg(Message.ROLE_USER, "第" + i + "轮问题"));
            list.add(msg(Message.ROLE_ASSISTANT, "第" + i + "轮回答"));
        }
        return list;
    }

    @Test
    @DisplayName("到期判定：消息数差 ≥ intervalRounds×2 才触发（每满 10 轮）")
    void shouldFold_boundary() {
        assertTrue(ConversationSummaryService.shouldFold(20, 0, 10));   // 恰好 10 轮
        assertTrue(ConversationSummaryService.shouldFold(41, 20, 10));  // 折叠失败后的超期补折
        assertFalse(ConversationSummaryService.shouldFold(19, 0, 10));  // 差 1 条
        assertFalse(ConversationSummaryService.shouldFold(20, 20, 10)); // 已全覆盖
        assertFalse(ConversationSummaryService.shouldFold(0, 0, 10));   // 空会话
    }

    @Test
    @DisplayName("折叠输入只含 messages[covered..T)，空内容消息跳过")
    void renderFoldInput_slicesAndSkips() {
        List<Message> messages = rounds(3);
        // 在 covered 边界后插一条空 assistant，验证空内容跳过
        messages.add(2, msg(Message.ROLE_ASSISTANT, "  "));
        String out = ConversationSummaryService.renderFoldInput(messages, 2,
                appProperties.getChatHistory().getSummary());
        assertTrue(out.contains("第2轮问题"));
        assertTrue(out.contains("第3轮回答"));
        assertFalse(out.contains("第1轮"));
        assertFalse(out.contains("助手：\n"));
    }

    @Test
    @DisplayName("自适应截断：条数多时按总量上限收紧单条（存量长会话首折兜底）")
    void renderFoldInput_adaptiveTruncation() {
        AppProperties.SummaryConfig cfg = appProperties.getChatHistory().getSummary();
        // 100 条 × 600 字 = 60000 字 > 30000 上限 → 单条收紧到 300 字
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            messages.add(msg(Message.ROLE_USER, "长".repeat(600)));
        }
        String out = ConversationSummaryService.renderFoldInput(messages, 0, cfg);
        for (String line : out.split("\n")) {
            // 前缀"用户：" + 300 字截断 + "…（截断）" ≈ 308
            assertTrue(line.length() <= 310, "单行超限: " + line.length());
        }
        assertTrue(out.contains("…（截断）"));
    }

    @Test
    @DisplayName("折叠提示词：已有摘要 + 新增轮次两段；首次折叠无已有摘要段")
    void buildFoldPrompt_includesOldSummary() {
        String withOld = ConversationSummaryService.buildFoldPrompt("旧摘要", "新轮次内容");
        assertTrue(withOld.contains("【已有记忆摘要】"));
        assertTrue(withOld.contains("旧摘要"));
        assertTrue(withOld.contains("【新增对话轮次】"));
        assertTrue(withOld.contains("新轮次内容"));

        String first = ConversationSummaryService.buildFoldPrompt(null, "新轮次内容");
        assertFalse(first.contains("【已有记忆摘要】"));
        assertTrue(first.contains("新轮次内容"));
    }

    @Test
    @DisplayName("预检：摘要功能关闭时不触发，且不查库")
    void tryBeginFold_disabled() {
        appProperties.getChatHistory().getSummary().setEnabled(false);
        assertFalse(service.tryBeginFold(1L));
        verifyNoInteractions(conversationService, conversationRepository);
    }

    @Test
    @DisplayName("预检：未到期（差 1 条）不触发")
    void tryBeginFold_notDue() {
        when(conversationService.listMessages(1L)).thenReturn(rounds(9));   // 18 条 < 20
        Conversation conv = new Conversation();
        conv.setSummaryCoveredMessages(0);
        when(conversationRepository.findById(1L)).thenReturn(Optional.of(conv));
        assertFalse(service.tryBeginFold(1L));
        verify(conversationService, never()).updateSummary(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("预检：会话不存在不触发")
    void tryBeginFold_conversationMissing() {
        when(conversationService.listMessages(1L)).thenReturn(rounds(10));
        when(conversationRepository.findById(1L)).thenReturn(Optional.empty());
        assertFalse(service.tryBeginFold(1L));
        verify(conversationService, never()).updateSummary(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("预检 + 防重：到期占位成功，进行中二次预检被拒，doFold 结束后释放占位")
    void tryBeginFold_inFlightGuardAndRelease() {
        when(conversationService.listMessages(1L)).thenReturn(rounds(10));  // 20 条，到期
        Conversation conv = new Conversation();
        conv.setSummaryCoveredMessages(0);
        when(conversationRepository.findById(1L)).thenReturn(Optional.of(conv));

        assertTrue(service.tryBeginFold(1L));    // 到期，占位成功
        assertFalse(service.tryBeginFold(1L));   // 折叠进行中，防重拒绝

        // 用会话缺失的早退路径执行 doFold → finally 释放占位
        when(conversationRepository.findById(1L)).thenReturn(Optional.empty());
        service.doFold(1L, 9L);
        verify(conversationService, never()).updateSummary(anyLong(), anyString(), anyInt());

        // 占位已释放，可再次触发
        when(conversationRepository.findById(1L)).thenReturn(Optional.of(conv));
        assertTrue(service.tryBeginFold(1L));
    }

    @Test
    @DisplayName("折叠输入全为空内容时：只推进覆盖进度保留原摘要，不调模型")
    void doFold_blankInputAdvancesCoveredOnly() {
        List<Message> blanks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            blanks.add(msg(i % 2 == 0 ? Message.ROLE_USER : Message.ROLE_ASSISTANT, " "));
        }
        when(conversationService.listMessages(1L)).thenReturn(blanks);
        Conversation conv = new Conversation();
        conv.setSummary("旧摘要");
        conv.setSummaryCoveredMessages(0);
        when(conversationRepository.findById(1L)).thenReturn(Optional.of(conv));

        service.doFold(1L, 9L);

        verify(conversationService).updateSummary(1L, "旧摘要", 20);
        verifyNoInteractions(summaryModel);
    }
}
