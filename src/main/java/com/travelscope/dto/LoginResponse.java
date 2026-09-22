package com.travelscope.dto;

/**
 * 登录响应
 */
public class LoginResponse {

    private String token;
    private Long userId;
    private String role;
    private String nickname;

    public LoginResponse() {
    }

    public LoginResponse(String token, Long userId, String role, String nickname) {
        this.token = token;
        this.userId = userId;
        this.role = role;
        this.nickname = nickname;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }
}
