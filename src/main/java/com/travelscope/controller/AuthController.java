package com.travelscope.controller;

import com.travelscope.dto.LoginRequest;
import com.travelscope.dto.LoginResponse;
import com.travelscope.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证接口：登录/登出（FR-A01~A03 管理端认证入口）
 * <p>
 * {@code POST /api/auth/login}：校验 users 表 BCrypt 密码 → 颁发 Redis token
 * （UUID，TTL 24h）；此端点在 AuthInterceptor 中放行（/auth/login 排除）。
 * {@code POST /api/auth/logout}：删除 token（需要有效 token 才能调用）。
 * </p>
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录：返回 token / userId / role / nickname
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        String token = authService.login(request.getUsername(), request.getPassword());
        if (token == null) {
            Map<String, Object> body = new HashMap<>();
            body.put("code", HttpStatus.UNAUTHORIZED.value());
            body.put("message", "用户名或密码错误，或账号已禁用");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        }
        // 从 token 反查用户拿 role/nickname（login 内部已校验，必存在）
        return authService.resolveUser(token)
                .<ResponseEntity<?>>map(user -> ResponseEntity.ok(
                        new LoginResponse(token, user.getId(), user.getRole(), user.getNickname())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }

    /**
     * 登出：删除当前 token
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            authService.logout(header.substring("Bearer ".length()).trim());
        }
        Map<String, Object> body = new HashMap<>();
        body.put("message", "已登出");
        return ResponseEntity.ok(body);
    }
}
