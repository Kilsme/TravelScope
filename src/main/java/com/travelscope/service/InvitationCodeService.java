package com.travelscope.service;

import com.travelscope.entity.InvitationCode;
import com.travelscope.repository.InvitationCodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 邀请码服务（2026-10-11：新用户仅凭有效邀请码注册，管理员发放）
 * <p>
 * 码的生命周期：管理员 generate（used_by 为空=未使用）→ 注册时 consume
 * （标记 used_by/used_at，一次性）。消费校验与注册落库在同一事务
 * （AuthService.register），码无效则用户创建整体回滚。
 * </p>
 */
@Service
public class InvitationCodeService {

    private static final Logger log = LoggerFactory.getLogger(InvitationCodeService.class);

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";   // 去易混字符（0/O/1/I）
    private static final int CODE_LENGTH = 8;

    private final InvitationCodeRepository invitationCodeRepository;
    private final SecureRandom random = new SecureRandom();

    public InvitationCodeService(InvitationCodeRepository invitationCodeRepository) {
        this.invitationCodeRepository = invitationCodeRepository;
    }

    /** 管理员发放：生成随机 8 位码入库 */
    @Transactional
    public InvitationCode generate(Long adminUserId) {
        String code;
        do {
            code = randomCode();
        } while (invitationCodeRepository.findByCode(code).isPresent());
        InvitationCode ic = new InvitationCode();
        ic.setCode(code);
        ic.setCreatedBy(adminUserId);
        InvitationCode saved = invitationCodeRepository.save(ic);
        log.info("邀请码已发放: code={}, 发放人 userId={}", code, adminUserId);
        return saved;
    }

    /** 管理端列表（新→旧） */
    public List<InvitationCode> listAll() {
        return invitationCodeRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * 消费邀请码（注册事务内调用）：存在且未使用 → 标记使用人/时间；
     * 无效（不存在/已使用）→ 抛 IllegalArgumentException（注册事务整体回滚）
     */
    @Transactional
    public void consume(String code, Long userId) {
        InvitationCode ic = invitationCodeRepository.findByCode(code == null ? "" : code.trim())
                .orElseThrow(() -> new IllegalArgumentException("邀请码无效"));
        if (ic.getUsedBy() != null) {
            throw new IllegalArgumentException("邀请码已被使用");
        }
        ic.setUsedBy(userId);
        ic.setUsedAt(LocalDateTime.now());
        invitationCodeRepository.save(ic);
        log.info("邀请码已使用: code={}, 使用人 userId={}", code, userId);
    }

    /** 可用性预检（注册流程先验码再建用户；consume 内的复查仍是权威判定） */
    public void validateUsable(String code) {
        InvitationCode ic = invitationCodeRepository.findByCode(code == null ? "" : code.trim())
                .orElseThrow(() -> new IllegalArgumentException("邀请码无效"));
        if (ic.getUsedBy() != null) {
            throw new IllegalArgumentException("邀请码已被使用");
        }
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }
}
