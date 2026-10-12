package com.travelscope.service;

import com.travelscope.config.AppProperties;
import com.travelscope.entity.Conversation;
import com.travelscope.repository.ConversationRepository;
import com.travelscope.repository.MessageRepository;
import com.travelscope.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ConversationService.updateSummaryChecked 双校验写入协议单测（P2-H 协议核心）
 * <p>
 * 内存代理仓储（TokenUsageServiceTest 同款动态代理模式），直测：
 * ①水位匹配 → 提交；②并发推进（当前水位 ≠ 折叠起点快照）→ 拒绝且不覆盖；
 * ③水位回退（新水位 < 当前水位）→ 拒绝。
 * </p>
 */
class ConversationServiceCheckedWriteTest {

    private static ConversationRepository inMemoryRepo(Map<Long, Conversation> store) {
        return (ConversationRepository) java.lang.reflect.Proxy.newProxyInstance(
                ConversationServiceCheckedWriteTest.class.getClassLoader(),
                new Class<?>[]{ConversationRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.ofNullable(store.get((Long) args[0]));
                    case "save" -> {
                        Conversation c = (Conversation) args[0];
                        if (c.getId() == null) {
                            c.setId(1L);
                        }
                        store.put(c.getId(), c);
                        yield c;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ConversationService newService(Map<Long, Conversation> store) {
        UserRepository users = (UserRepository) java.lang.reflect.Proxy.newProxyInstance(
                ConversationServiceCheckedWriteTest.class.getClassLoader(),
                new Class<?>[]{UserRepository.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
        MessageRepository messages = (MessageRepository) java.lang.reflect.Proxy.newProxyInstance(
                ConversationServiceCheckedWriteTest.class.getClassLoader(),
                new Class<?>[]{MessageRepository.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
        return new ConversationService(users, inMemoryRepo(store), messages, new AppProperties());
    }

    private static Conversation convWith(long id, String summary, int covered) {
        Conversation c = new Conversation();
        c.setId(id);
        c.setSummary(summary);
        c.setSummaryCoveredMessages(covered);
        return c;
    }

    @Test
    @DisplayName("水位匹配（当前=快照且不回退）→ 提交成功，summary 与水位一并写回")
    void testCommitWhenWatermarkMatches() {
        Map<Long, Conversation> store = new HashMap<>();
        store.put(1L, convWith(1L, "旧摘要", 10));
        ConversationService service = newService(store);

        boolean committed = service.updateSummaryChecked(1L, "新摘要", 10, 20);

        assertTrue(committed, "水位匹配应提交");
        assertEquals("新摘要", store.get(1L).getSummary());
        assertEquals(20, store.get(1L).getSummaryCoveredMessages());
    }

    @Test
    @DisplayName("并发推进（当前水位 ≠ 折叠起点快照）→ 拒绝，不覆盖他方结果")
    void testRejectWhenWatermarkAdvancedByOther() {
        Map<Long, Conversation> store = new HashMap<>();
        // 他方（另一实例/并发任务）已把水位从 10 推进到 18、摘要更新为「他方摘要」
        store.put(1L, convWith(1L, "他方摘要", 18));
        ConversationService service = newService(store);

        boolean committed = service.updateSummaryChecked(1L, "本地方旧摘要", 10, 20);

        assertFalse(committed, "并发推进应拒绝提交（防 last-write-wins 覆盖）");
        assertEquals("他方摘要", store.get(1L).getSummary(), "他方结果不被覆盖");
        assertEquals(18, store.get(1L).getSummaryCoveredMessages(), "他方水位不被覆盖");
    }

    @Test
    @DisplayName("水位回退（新水位 < 当前水位）→ 拒绝")
    void testRejectWhenWatermarkRegresses() {
        Map<Long, Conversation> store = new HashMap<>();
        store.put(1L, convWith(1L, "旧摘要", 20));
        ConversationService service = newService(store);

        boolean committed = service.updateSummaryChecked(1L, "新摘要", 20, 15);

        assertFalse(committed, "水位回退应拒绝");
        assertEquals("旧摘要", store.get(1L).getSummary());
        assertEquals(20, store.get(1L).getSummaryCoveredMessages());
    }

    @Test
    @DisplayName("会话不存在 → 拒绝（不抛异常）")
    void testRejectWhenConversationMissing() {
        Map<Long, Conversation> store = new HashMap<>();
        ConversationService service = newService(store);

        assertFalse(service.updateSummaryChecked(99L, "摘要", 0, 10));
    }
}
