package com.travelscope.security;

import com.alibaba.fastjson2.JSON;
import com.travelscope.entity.User;
import com.travelscope.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 认证拦截器（FR-A01~A03：全站 /api/** 统一认证 + 角色注入）
 * <p>
 * 生效范围：{@code /api/**}（除 {@code /api/auth/login} 与 OPTIONS 预检）——
 * 管理端与聊天端统一走认证（全站禁用体系，用户决策）。解析
 * {@code Authorization: Bearer <token>} → AuthService.resolveUser →
 * 通过注入 request attribute {@code currentUser} 与 {@code userRole}，
 * Controller 直接从 attribute 取（不再走 guest 共享）。
 * </p>
 * <p>
 * 401 格式与 GlobalExceptionHandler 对齐（{@code {code, message}}），
 * 拦截器层异常不经 @RestControllerAdvice，须自行序列化。
 * </p>
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    public static final String ATTR_CURRENT_USER = "currentUser";
    public static final String ATTR_USER_ROLE = "userRole";

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;

    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // OPTIONS 预检放行（CORS）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "未授权（缺少或格式错误的 Authorization 头）");
            return false;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        java.util.Optional<User> userOpt = authService.resolveUser(token);
        if (userOpt.isEmpty()) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "未授权或账号已禁用");
            log.info("认证拒绝: {} {}（无效/过期/禁用 token）", request.getMethod(), request.getRequestURI());
            return false;
        }
        User user = userOpt.get();
        request.setAttribute(ATTR_CURRENT_USER, user);
        request.setAttribute(ATTR_USER_ROLE, user.getRole());
        return true;
    }

    /**
     * 401 响应（{code, message} 与全局异常格式一致）
     */
    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Map<String, Object> body = new HashMap<>();
        body.put("code", status);
        body.put("message", message);
        response.getWriter().write(JSON.toJSONString(body));
    }
}
