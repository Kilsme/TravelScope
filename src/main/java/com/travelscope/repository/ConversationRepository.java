package com.travelscope.repository;

import com.travelscope.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    List<Conversation> findByUserIdAndStatusOrderByUpdatedAtDesc(Long userId, String status);

    Optional<Conversation> findByIdAndUserId(Long id, Long userId);
}
