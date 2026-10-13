package com.travelscope.repository;

import com.travelscope.entity.InvitationCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 邀请码仓储（2026-10-11 注册准入）
 */
public interface InvitationCodeRepository extends JpaRepository<InvitationCode, Long> {

    Optional<InvitationCode> findByCode(String code);

    List<InvitationCode> findAllByOrderByCreatedAtDesc();
}
