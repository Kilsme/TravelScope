package com.travelscope.service;

import com.travelscope.entity.InvitationCode;
import com.travelscope.repository.InvitationCodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InvitationCodeService 单测（2026-10-11 邀请码准入：发放/消费/预检）
 */
class InvitationCodeServiceTest {

    private InvitationCodeRepository repo;
    private InvitationCodeService service;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(InvitationCodeRepository.class);
        service = new InvitationCodeService(repo);
    }

    private static InvitationCode code(String code, Long usedBy) {
        InvitationCode ic = new InvitationCode();
        ic.setId(1L);
        ic.setCode(code);
        ic.setCreatedBy(1L);
        ic.setUsedBy(usedBy);
        return ic;
    }

    @Test
    @DisplayName("发放：生成 8 位码入库（去易混字符字母表）")
    void testGenerate() {
        Mockito.when(repo.findByCode(Mockito.anyString())).thenReturn(Optional.empty());
        Mockito.when(repo.save(Mockito.any())).thenAnswer(inv -> inv.getArgument(0));

        InvitationCode ic = service.generate(1L);

        assertNotNull(ic.getCode());
        assertEquals(8, ic.getCode().length());
        assertTrue(ic.getCode().chars().allMatch(c ->
                "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".indexOf(c) >= 0), "码应只含去易混字符");
        assertEquals(1L, ic.getCreatedBy());
        assertNull(ic.getUsedBy(), "新码未使用");
    }

    @Test
    @DisplayName("消费：未使用码 → 标记使用人/时间")
    void testConsumeSuccess() {
        InvitationCode ic = code("ABCD2345", null);
        Mockito.when(repo.findByCode("ABCD2345")).thenReturn(Optional.of(ic));
        Mockito.when(repo.save(Mockito.any())).thenAnswer(inv -> inv.getArgument(0));

        service.consume("ABCD2345", 42L);

        assertEquals(42L, ic.getUsedBy());
        assertNotNull(ic.getUsedAt());
    }

    @Test
    @DisplayName("消费拒绝：码不存在 / 已使用")
    void testConsumeRejected() {
        Mockito.when(repo.findByCode("NOPE")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.consume("NOPE", 1L));

        Mockito.when(repo.findByCode("USED1111")).thenReturn(Optional.of(code("USED1111", 7L)));
        assertThrows(IllegalArgumentException.class, () -> service.consume("USED1111", 8L));
    }

    @Test
    @DisplayName("预检 validateUsable：未使用通过 / 已使用或不存在拒绝")
    void testValidateUsable() {
        Mockito.when(repo.findByCode("OK")).thenReturn(Optional.of(code("OK", null)));
        service.validateUsable("OK");   // 不抛即通过

        Mockito.when(repo.findByCode("USED")).thenReturn(Optional.of(code("USED", 5L)));
        assertThrows(IllegalArgumentException.class, () -> service.validateUsable("USED"));
        assertThrows(IllegalArgumentException.class, () -> service.validateUsable(null));
    }
}
