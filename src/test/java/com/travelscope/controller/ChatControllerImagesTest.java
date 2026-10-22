package com.travelscope.controller;

import com.travelscope.entity.Message;
import com.travelscope.service.ChatService;
import com.travelscope.service.ConversationService;
import com.travelscope.service.MinioService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 聊天图片能力端点测试（私人讲解员，2026-10-22）：
 * nonce 匹配 content_blocks 引用 → 200 流式回写；错配/无引用/消息不存在一律 404（防探测）。
 * standalone MockMvc（同 AssessmentReviewRegressionTest 的 controller 测试先例）。
 */
class ChatControllerImagesTest {

    private static final String OBJECT_KEY = "chat-images/20261022/5f8d3a2b7c9e41d0a6b2c8f1e3d4a5b6.jpg";
    private static final String NONCE = "5f8d3a2b7c9e41d0a6b2c8f1e3d4a5b6";

    private final ChatService chatService = mock(ChatService.class);
    private final ConversationService conversationService = mock(ConversationService.class);
    private final MinioService minioService = mock(MinioService.class);

    private org.springframework.test.web.servlet.MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(
                new ChatController(chatService, conversationService, minioService)).build();
    }

    private static Message imageMessage(Long id, String contentBlocks) {
        Message m = new Message();
        java.lang.reflect.Field idField;
        try {
            idField = Message.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(m, id);
            m.setContentBlocks(contentBlocks);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return m;
    }

    @Test
    @DisplayName("nonce 匹配 → 200 回写 MinIO 对象字节 + Content-Type")
    void matchingNonceStreamsObject() throws Exception {
        when(conversationService.getMessage(7L)).thenReturn(imageMessage(7L,
                "{\"images\":[{\"mediaType\":\"image/jpeg\",\"objectKey\":\"" + OBJECT_KEY + "\"}]}"));
        byte[] bytes = "jpeg-bytes".getBytes(StandardCharsets.UTF_8);
        when(minioService.download(OBJECT_KEY)).thenReturn(new ByteArrayInputStream(bytes));

        mvc().perform(get("/api/chat/images/7/" + NONCE + ".jpg").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes))
                .andExpect(content().contentType("image/jpeg"));
    }

    @Test
    @DisplayName("nonce 错配 → 404（capability token 不匹配，防枚举）")
    void wrongNonceIs404() throws Exception {
        when(conversationService.getMessage(7L)).thenReturn(imageMessage(7L,
                "{\"images\":[{\"mediaType\":\"image/jpeg\",\"objectKey\":\"" + OBJECT_KEY + "\"}]}"));

        mvc().perform(get("/api/chat/images/7/deadbeef.jpg".replace("deadbeef", "0" + NONCE.substring(1)))
                        .contextPath("/api"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("消息不存在/无图片引用 → 404")
    void missingMessageOrReferenceIs404() throws Exception {
        when(conversationService.getMessage(8L)).thenReturn(null);
        when(conversationService.getMessage(9L)).thenReturn(imageMessage(9L, null));

        mvc().perform(get("/api/chat/images/8/" + NONCE + ".jpg").contextPath("/api"))
                .andExpect(status().isNotFound());
        mvc().perform(get("/api/chat/images/9/" + NONCE + ".jpg").contextPath("/api"))
                .andExpect(status().isNotFound());
    }
}
