package com.travelscope.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 对话请求体
 * <p>
 * 私人讲解员（图片提问）：imageBase64 携带单张图片（前端 canvas 压缩后的裸 base64，
 * 不带 data: 前缀）；message 可为空（缺省问题「讲解一下这个景点」）——文本与图片至少其一。
 * base64 仅在内存中随调用链传给多模态模型，落库的是 MinIO 对象引用（content_blocks）。
 * </p>
 */
@Getter
@Setter
public class ChatRequest {

    /** 会话 ID；为空表示新建会话（自动创建并回传会话信息） */
    private Long conversationId;

    /** 用户消息文本（图片轮可为空） */
    @Size(max = 8000, message = "消息过长（最多 8000 字符）")
    private String message;

    /** 图片裸 base64（不含 data: 前缀；上限约 1.5MB 二进制，网关请求体 2MiB 硬上限内） */
    @Size(max = 2_000_000, message = "图片过大（压缩后请控制在 1.5MB 以内）")
    private String imageBase64;

    /** 文本与图片至少其一（原 message @NotBlank 的图片轮放宽形态） */
    @AssertTrue(message = "消息不能为空（或附带一张图片）")
    public boolean isTurnPresent() {
        return (message != null && !message.isBlank())
                || (imageBase64 != null && !imageBase64.isBlank());
    }
}
