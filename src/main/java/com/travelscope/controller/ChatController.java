package com.travelscope.controller;

import com.alibaba.fastjson2.JSON;
import com.travelscope.dto.ChatRequest;
import com.travelscope.dto.ConversationVO;
import com.travelscope.dto.MessageVO;
import com.travelscope.entity.Conversation;
import com.travelscope.entity.Message;
import com.travelscope.entity.User;
import com.travelscope.security.AuthInterceptor;
import com.travelscope.service.ChatService;
import com.travelscope.service.ConversationService;
import com.travelscope.service.MinioService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final MinioService minioService;

    public ChatController(ChatService chatService, ConversationService conversationService,
                          MinioService minioService) {
        this.chatService = chatService;
        this.conversationService = conversationService;
        this.minioService = minioService;
    }

    /**
     * SSE 流式对话
     * <p>
     * 事件序列：intent（意图分类结果）→ delta/tool（N 次）→ done（完整回复）或 error。
     * conversationId 为空时自动新建会话。imageBase64（私人讲解员图片轮）可为空。
     * 认证：AuthInterceptor 已把当前用户注入 request attribute（不再走 guest 共享）。
     * </p>
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody ChatRequest request,
                                 HttpServletRequest httpRequest) {
        User user = currentUser(httpRequest);

        Conversation conversation = request.getConversationId() != null
                ? conversationService.getOwnedConversation(request.getConversationId(), user.getId())
                : conversationService.createConversation(user.getId());

        return chatService.streamChat(conversation, user.getId(), request.getMessage(),
                request.getImageBase64());
    }

    /**
     * 聊天图片能力端点（私人讲解员）：{@code GET /chat/images/{messageId}/{nonce}.{ext}}
     * <p>
     * <img> 标签不带 Bearer——本前缀经 WebConfig 放行认证，nonce（content_blocks 里
     * objectKey 的 uuid 段）即 capability token：服务端按消息引用校验，一致才流式回写
     * MinIO 对象；不存在/引用缺失/nonce 错配一律 404（不区分形态，防探测枚举）。
     * MinIO endpoint（localhost:9000）对浏览器不保证可达，代理回写对部署拓扑免疫。
     * </p>
     */
    @GetMapping("/images/{messageId}/{filename}")
    public ResponseEntity<InputStreamResource> chatImage(@PathVariable Long messageId,
                                                         @PathVariable String filename) {
        String nonce = filename.contains(".")
                ? filename.substring(0, filename.lastIndexOf('.')) : filename;
        Message message = conversationService.getMessage(messageId);
        String objectKey = null;
        String mediaType = null;
        if (message != null && message.getContentBlocks() != null) {
            try {
                var images = JSON.parseObject(message.getContentBlocks()).getJSONArray("images");
                if (images != null && !images.isEmpty()) {
                    objectKey = images.getJSONObject(0).getString("objectKey");
                    mediaType = images.getJSONObject(0).getString("mediaType");
                }
            } catch (Exception ignored) {
                // 引用 JSON 异常按无图处理
            }
        }
        if (objectKey == null) {
            return ResponseEntity.notFound().build();
        }
        String tail = objectKey.substring(objectKey.lastIndexOf('/') + 1);
        int dot = tail.lastIndexOf('.');
        String expected = dot > 0 ? tail.substring(0, dot) : tail;
        if (!expected.equals(nonce)) {
            return ResponseEntity.notFound().build();
        }
        try {
            // 流由 Spring 的 Resource 消息转换器负责关闭（不能在此 try-with-resources 提前关）
            return ResponseEntity.ok()
                    .header("Content-Type", mediaType != null ? mediaType : "application/octet-stream")
                    .header("Cache-Control", "private, max-age=86400")
                    .body(new InputStreamResource(minioService.download(objectKey)));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 会话列表
     */
    @GetMapping("/conversations")
    public List<ConversationVO> listConversations(HttpServletRequest httpRequest) {
        User user = currentUser(httpRequest);
        return conversationService.listConversations(user.getId())
                .stream().map(ConversationVO::from).toList();
    }

    /**
     * 新建会话
     */
    @PostMapping("/conversations")
    public ConversationVO createConversation(HttpServletRequest httpRequest) {
        User user = currentUser(httpRequest);
        return ConversationVO.from(conversationService.createConversation(user.getId()));
    }

    /**
     * 会话历史消息
     */
    @GetMapping("/conversations/{id}/messages")
    public List<MessageVO> listMessages(@PathVariable Long id, HttpServletRequest httpRequest) {
        User user = currentUser(httpRequest);
        Conversation conversation = conversationService.getOwnedConversation(id, user.getId());
        return conversationService.listMessages(conversation.getId())
                .stream().map(MessageVO::from).toList();
    }

    /**
     * 从 AuthInterceptor 注入的 request attribute 取当前用户
     * （拦截器已校验 token 有效性，attribute 必然存在）
     */
    private User currentUser(HttpServletRequest request) {
        return (User) request.getAttribute(AuthInterceptor.ATTR_CURRENT_USER);
    }
}
