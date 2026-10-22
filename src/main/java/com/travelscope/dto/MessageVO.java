package com.travelscope.dto;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.travelscope.entity.Message;
import lombok.Getter;
import lombok.Setter;

import java.time.format.DateTimeFormatter;

/**
 * 消息视图对象
 * <p>
 * image（私人讲解员图片轮）：从 content_blocks 引用解析出的能力 URL
 * {@code /api/chat/images/{messageId}/{nonce}.jpg}（nonce = objectKey 的 uuid 段，
 * 即 capability token——<img> 标签不带 Bearer，端点经 WebConfig 放行后靠 nonce 防枚举）。
 * 纯文本消息 image 为 null。
 * </p>
 */
@Getter
@Setter
public class MessageVO {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Long id;
    private String role;
    private String content;
    private String createdAt;
    /** 图片能力 URL（null = 纯文本消息）；扩展名按引用 mediaType 映射 */
    private String image;

    public static MessageVO from(Message m) {
        MessageVO vo = new MessageVO();
        vo.setId(m.getId());
        vo.setRole(m.getRole());
        vo.setContent(m.getContent());
        vo.setCreatedAt(m.getCreatedAt() != null ? m.getCreatedAt().format(FMT) : null);
        vo.setImage(imageCapabilityUrl(m));
        return vo;
    }

    /** content_blocks 引用 → 能力 URL；解析失败/无图片引用返回 null（存量消息兼容） */
    private static String imageCapabilityUrl(Message m) {
        if (m.getContentBlocks() == null || m.getId() == null) {
            return null;
        }
        try {
            JSONArray images = JSON.parseObject(m.getContentBlocks()).getJSONArray("images");
            if (images == null || images.isEmpty()) {
                return null;
            }
            JSONObject ref = images.getJSONObject(0);
            String objectKey = ref.getString("objectKey");
            if (objectKey == null) {
                return null;
            }
            // nonce = objectKey 末段文件名去扩展名（uuid32），图片能力端点按此校验
            String nonce = objectKey.substring(objectKey.lastIndexOf('/') + 1);
            int dot = nonce.lastIndexOf('.');
            if (dot > 0) {
                nonce = nonce.substring(0, dot);
            }
            String mediaType = ref.getString("mediaType");
            String ext = mediaType != null && mediaType.contains("png") ? ".png"
                    : mediaType != null && mediaType.contains("webp") ? ".webp" : ".jpg";
            return "/api/chat/images/" + m.getId() + "/" + nonce + ext;
        } catch (Exception e) {
            return null;
        }
    }
}
