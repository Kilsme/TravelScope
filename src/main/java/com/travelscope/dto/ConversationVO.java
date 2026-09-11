package com.travelscope.dto;

import com.travelscope.entity.Conversation;
import lombok.Getter;
import lombok.Setter;

import java.time.format.DateTimeFormatter;

/**
 * 会话视图对象
 */
@Getter
@Setter
public class ConversationVO {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Long id;
    private String title;
    private String updatedAt;

    public static ConversationVO from(Conversation c) {
        ConversationVO vo = new ConversationVO();
        vo.setId(c.getId());
        vo.setTitle(c.getTitle());
        vo.setUpdatedAt(c.getUpdatedAt() != null ? c.getUpdatedAt().format(FMT) : null);
        return vo;
    }
}
