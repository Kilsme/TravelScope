package com.travelscope.service;

import com.travelscope.config.AppProperties;
import com.travelscope.entity.User;
import com.travelscope.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PointsService 单测（2026-10-11 积分制度：预扣/发放/余额）
 * <p>
 * UserRepository 用 Mockito 桩：deductPoints/adjustPoints 的影响行数语义
 * （1=成功 0=余额不足或用户不存在）即原子 SQL 的契约，语义核验由集成环境承担。
 * </p>
 */
class PointsServiceTest {

    private UserRepository userRepository;
    private PointsService service;

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        service = new PointsService(userRepository, new AppProperties());
    }

    private static User userWith(long id, long balance) {
        User u = new User();
        u.setId(id);
        u.setUsername("u" + id);
        u.setPointsBalance(balance);
        return u;
    }

    @Test
    @DisplayName("额度充足 → 预扣成功（deductPoints 影响行数=1）")
    void testDeductSuccess() {
        Mockito.when(userRepository.deductPoints(1L, 50)).thenReturn(1);
        Mockito.when(userRepository.findById(1L)).thenReturn(Optional.of(userWith(1L, 450)));

        assertTrue(service.tryDeduct(1L, 50));
    }

    @Test
    @DisplayName("余额不足 → 预扣拒绝（影响行数=0，本轮不应执行）")
    void testDeductInsufficient() {
        Mockito.when(userRepository.deductPoints(1L, 200)).thenReturn(0);

        assertFalse(service.tryDeduct(1L, 200));
    }

    @Test
    @DisplayName("管理员发放/回收：调整成功返回新余额；用户不存在抛错")
    void testGrant() {
        Mockito.when(userRepository.adjustPoints(1L, 500)).thenReturn(1);
        Mockito.when(userRepository.findById(1L)).thenReturn(Optional.of(userWith(1L, 1000)));
        assertEquals(1000L, service.grant(1L, 500));

        Mockito.when(userRepository.adjustPoints(99L, 100)).thenReturn(0);
        assertThrows(IllegalArgumentException.class, () -> service.grant(99L, 100));
    }

    @Test
    @DisplayName("计费档位：PLANNING=200 / 普通=50 / 意图未知兜底普通档")
    void testCostOf() {
        assertEquals(200, service.costOf(true));
        assertEquals(50, service.costOf(false));
    }

    @Test
    @DisplayName("余额查询：用户不存在按 0 计")
    void testBalanceOf() {
        Mockito.when(userRepository.findById(1L)).thenReturn(Optional.of(userWith(1L, 300)));
        assertEquals(300L, service.balanceOf(1L));

        Mockito.when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertEquals(0L, service.balanceOf(99L));
    }
}
