package com.travelscope.controller;

import com.travelscope.dto.ChatRequest;
import com.travelscope.dto.ConversationVO;
import com.travelscope.dto.MessageVO;
import com.travelscope.entity.Conversation;
import com.travelscope.entity.User;
import com.travelscope.service.ChatService;
import com.travelscope.service.ConversationService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 对话接口：SSE 流式对话 + 会话管理
 * <p>
 * 前端通过 fetch 读取 POST /chat/stream 的 SSE 流（POST + SSE 支持长消息，
 * EventSource 仅支持 GET 故不采用）。
 * </p>
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;
    private final ConversationService conversationService;

    public ChatController(ChatService chatService, ConversationService conversationService) {
        this.chatService = chatService;
        this.conversationService = conversationService;
    }

    /**
     * SSE 流式对话
     * <p>
     * 事件序列：intent（意图分类结果）→ delta/tool（N 次）→ done（完整回复）或 error。
     * conversationId 为空时自动新建会话。
     * </p>
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody ChatRequest request) {
        User user = conversationService.getOrCreateGuestUser();

        Conversation conversation = request.getConversationId() != null
                ? conversationService.getOwnedConversation(request.getConversationId(), user.getId())
                : conversationService.createConversation(user.getId());

        return chatService.streamChat(conversation, user.getId(), request.getMessage());
    }

    /**
     * 会话列表
     */
    @GetMapping("/conversations")
    public List<ConversationVO> listConversations() {
        User user = conversationService.getOrCreateGuestUser();
        return conversationService.listConversations(user.getId())
                .stream().map(ConversationVO::from).toList();
    }

    /**
     * 新建会话
     */
    @PostMapping("/conversations")
    public ConversationVO createConversation() {
        User user = conversationService.getOrCreateGuestUser();
        return ConversationVO.from(conversationService.createConversation(user.getId()));
    }

    /**
     * 会话历史消息
     */
    @GetMapping("/conversations/{id}/messages")
    public List<MessageVO> listMessages(@PathVariable Long id) {
        User user = conversationService.getOrCreateGuestUser();
        Conversation conversation = conversationService.getOwnedConversation(id, user.getId());
        return conversationService.listMessages(conversation.getId())
                .stream().map(MessageVO::from).toList();
    }
}
