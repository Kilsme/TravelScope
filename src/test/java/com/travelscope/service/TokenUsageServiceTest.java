package com.travelscope.service;

import com.travelscope.dto.TokenUsageVO;
import com.travelscope.entity.User;
import com.travelscope.repository.MessageRepository;
import com.travelscope.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TokenUsageService 单元测试（FR-A01 报表组装：聚合行 → VO 含 username 展示名）
 */
class TokenUsageServiceTest {

    private TokenUsageService service;

    @BeforeEach
    void setUp() {
        // Fake MessageRepository：返回固定聚合行
        MessageRepository messageRepository = (MessageRepository) java.lang.reflect.Proxy
                .newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{MessageRepository.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "aggregateTokenUsage" -> List.of(
                                    row(1L, "qwen-plus", Date.valueOf("2026-09-19"), 12L, 5000L),
                                    row(1L, "qwen-turbo", Date.valueOf("2026-09-19"), 3L, 800L),
                                    row(2L, "qwen-plus", Date.valueOf("2026-09-18"), 7L, 3200L));
                            default -> throw new UnsupportedOperationException(method.getName());
                        });

        // Fake UserRepository：findAll 返回两个用户
        UserRepository userRepository = (UserRepository) java.lang.reflect.Proxy
                .newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{UserRepository.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "findAll" -> List.of(
                                    user(1L, "admin"),
                                    user(2L, "user1"));
                            default -> throw new UnsupportedOperationException(method.getName());
                        });

        service = new TokenUsageService(messageRepository, userRepository);
    }

    private static User user(Long id, String username) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        return u;
    }

    /** 构造聚合行（TokenUsageRow 是 projection 接口，用动态代理实现 getter） */
    private static MessageRepository.TokenUsageRow row(Long userId, String modelName,
                                                       Date date, Long count, Long tokens) {
        return (MessageRepository.TokenUsageRow) java.lang.reflect.Proxy.newProxyInstance(
                TokenUsageServiceTest.class.getClassLoader(),
                new Class<?>[]{MessageRepository.TokenUsageRow.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUserId" -> userId;
                    case "getModelName" -> modelName;
                    case "getUsageDate" -> date;
                    case "getMessageCount" -> count;
                    case "getTotalTokens" -> tokens;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    @DisplayName("聚合行 → VO：username 展示名映射正确、字段透传")
    void testVoAssembly() {
        List<TokenUsageVO> rows = service.query(null, null, null);

        assertEquals(3, rows.size());
        TokenUsageVO first = rows.get(0);
        assertEquals(1L, first.getUserId());
        assertEquals("admin", first.getUsername(), "userId=1 应映射到 admin 用户名");
        assertEquals("2026-09-19", first.getDate());
        assertEquals("qwen-plus", first.getModelName());
        assertEquals(Long.valueOf(12), first.getMessageCount());
        assertEquals(Long.valueOf(5000), first.getTotalTokens());

        TokenUsageVO secondUser = rows.get(2);
        assertEquals("user1", secondUser.getUsername());
    }

    @Test
    @DisplayName("未知 userId 的 username 兜底为 user-{id}")
    void testUnknownUserFallback() {
        // 复用 service（Fake findAll 只含 id 1/2）；让聚合行带 id=99
        MessageRepository repo99 = (MessageRepository) java.lang.reflect.Proxy
                .newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{MessageRepository.class},
                        (proxy, method, args) -> List.of(
                                row(99L, "qwen-plus", Date.valueOf("2026-09-19"), 1L, 100L)));
        UserRepository userRepo = (UserRepository) java.lang.reflect.Proxy
                .newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{UserRepository.class},
                        (proxy, method, args) -> List.of(user(1L, "admin")));
        TokenUsageService s = new TokenUsageService(repo99, userRepo);

        List<TokenUsageVO> rows = s.query(null, null, null);
        assertEquals("user-99", rows.get(0).getUsername(), "未知用户应兜底 user-{id}");
    }
}
