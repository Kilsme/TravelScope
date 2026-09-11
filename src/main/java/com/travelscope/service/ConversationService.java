package com.travelscope.service;

import com.travelscope.entity.Conversation;
import com.travelscope.entity.Message;
import com.travelscope.entity.User;
import com.travelscope.repository.ConversationRepository;
import com.travelscope.repository.MessageRepository;
import com.travelscope.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 会话服务：匿名用户保障、会话 CRUD、消息持久化
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    /** 会话标题最大长度（首条消息截断生成） */
    private static final int TITLE_MAX_LEN = 30;

    private final UserRepository userRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    public ConversationService(UserRepository userRepository,
                               ConversationRepository conversationRepository,
                               MessageRepository messageRepository) {
        this.userRepository = userRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * 获取或创建匿名用户（当前无认证体系，所有会话归属 guest 用户）
     */
    @Transactional
    public User getOrCreateGuestUser() {
        return userRepository.findByUsername(User.GUEST_USERNAME).orElseGet(() -> {
            User guest = new User();
            guest.setUsername(User.GUEST_USERNAME);
            guest.setPassword("N/A");
            guest.setNickname("游客");
            User saved = userRepository.save(guest);
            log.info("已创建匿名用户: id={}, username={}", saved.getId(), saved.getUsername());
            return saved;
        });
    }

    /**
     * 获取用户全部活跃会话
     */
    public List<Conversation> listConversations(Long userId) {
        return conversationRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, "active");
    }

    /**
     * 新建会话
     */
    @Transactional
    public Conversation createConversation(Long userId) {
        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        return conversationRepository.save(conversation);
    }

    /**
     * 获取用户自己的会话，不存在或无权限时抛 IllegalArgumentException
     */
    public Conversation getOwnedConversation(Long conversationId, Long userId) {
        return conversationRepository.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));
    }

    /**
     * 获取会话消息列表（按时间升序）
     */
    public List<Message> listMessages(Long conversationId) {
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    /**
     * 保存用户消息；会话标题为默认值时用首条消息生成标题
     */
    @Transactional
    public void saveUserMessage(Conversation conversation, Long userId, String content) {
        Message message = new Message();
        message.setConversationId(conversation.getId());
        message.setUserId(userId);
        message.setRole(Message.ROLE_USER);
        message.setContent(content);
        messageRepository.save(message);

        if ("新会话".equals(conversation.getTitle()) && content != null && !content.isBlank()) {
            String title = content.trim();
            conversation.setTitle(title.length() > TITLE_MAX_LEN ? title.substring(0, TITLE_MAX_LEN) : title);
        }
        conversationRepository.save(conversation);
    }

    /**
     * 保存助手回复
     */
    @Transactional
    public void saveAssistantMessage(Conversation conversation, Long userId, String content) {
        Message message = new Message();
        message.setConversationId(conversation.getId());
        message.setUserId(userId);
        message.setRole(Message.ROLE_ASSISTANT);
        message.setContent(content);
        messageRepository.save(message);
        conversationRepository.save(conversation);
    }
}
