package com.travelscope.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 对话请求体
 */
@Getter
@Setter
public class ChatRequest {

    /** 会话 ID；为空表示新建会话（自动创建并回传会话信息） */
    private Long conversationId;

    /** 用户消息 */
    @NotBlank(message = "消息不能为空")
    @Size(max = 8000, message = "消息过长（最多 8000 字符）")
    private String message;
}
