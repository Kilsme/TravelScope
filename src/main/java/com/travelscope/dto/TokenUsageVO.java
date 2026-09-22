package com.travelscope.dto;

/**
 * Token 用量报表行（FR-A01，按用户/按天/按模型聚合）
 */
public class TokenUsageVO {

    private Long userId;
    private String username;
    private String date;
    private String modelName;
    private Long messageCount;
    private Long totalTokens;

    public TokenUsageVO() {
    }

    public TokenUsageVO(Long userId, String username, String date, String modelName,
                        Long messageCount, Long totalTokens) {
        this.userId = userId;
        this.username = username;
        this.date = date;
        this.modelName = modelName;
        this.messageCount = messageCount;
        this.totalTokens = totalTokens;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Long getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(Long messageCount) {
        this.messageCount = messageCount;
    }

    public Long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Long totalTokens) {
        this.totalTokens = totalTokens;
    }
}
