package com.travelscope.dto;

import com.travelscope.entity.Message;
import lombok.Getter;
import lombok.Setter;

import java.time.format.DateTimeFormatter;

/**
 * 消息视图对象
 */
@Getter
@Setter
public class MessageVO {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Long id;
    private String role;
    private String content;
    private String createdAt;

    public static MessageVO from(Message m) {
        MessageVO vo = new MessageVO();
        vo.setId(m.getId());
        vo.setRole(m.getRole());
        vo.setContent(m.getContent());
        vo.setCreatedAt(m.getCreatedAt() != null ? m.getCreatedAt().format(FMT) : null);
        return vo;
    }
}
