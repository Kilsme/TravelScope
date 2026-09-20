package com.travelscope.service;

import com.travelscope.entity.User;
import com.travelscope.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 认证服务（管理端 /admin/** + 全站禁用体系，FR-A01~A03 配套）
 * <p>
 * <b>Redis Token 模式</b>（用户决策 2026-09-19）：登录校验 users 表 BCrypt 密码 →
 * 生成 UUID token 存 Redis（key {@code auth:token:{token}}，value 序列化 userId|role，
 * TTL 24h）。优点：禁用用户即删全部 token 立即生效（检测标准 3 的「禁用即拒」）；
 * 与项目 Redis 缓存/降级风格一致；零外部依赖（不引入 JJWT）。
 * </p>
 * <p>
 * 用户状态机：users.status（0=禁用 / 1=启用）；role（user / admin）。
 * </p>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String TOKEN_KEY_PREFIX = "auth:token:";
    private static final Duration TOKEN_TTL = Duration.ofHours(24);

    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository userRepository, StringRedisTemplate redisTemplate) {
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 登录：校验用户名+BCrypt 密码，通过且用户启用 → 颁发 token
     *
     * @return token；失败（用户不存在/密码错/账号禁用）返回 null
     */
    public String login(String username, String password) {
        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            log.warn("登录失败（用户不存在）: {}", username);
            return null;
        }
        User user = userOpt.get();
        if (user.getStatus() != null && user.getStatus() == 0) {
            log.warn("登录失败（账号已禁用）: {} userId={}", username, user.getId());
            return null;
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            log.warn("登录失败（密码错误）: {}", username);
            return null;
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        saveToken(token, user.getId(), user.getRole());
        log.info("登录成功: {} userId={} role={}", username, user.getId(), user.getRole());
        return token;
    }

    /**
     * 登出：删除 token
     */
    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            redisTemplate.delete(TOKEN_KEY_PREFIX + token);
        }
    }

    /**
     * 解析 token → 用户（校验启用状态；禁用时删键返回 empty）
     * <p>
     * AuthInterceptor 调用点：每次请求都走这里——禁用在 Redis 无 token 时立即被拒，
     * 即使原 token 刚签发不久（disable 时已全删该用户的 token）。
     * </p>
     */
    public Optional<User> resolveUser(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String payload;
        try {
            payload = redisTemplate.opsForValue().get(TOKEN_KEY_PREFIX + token);
        } catch (Exception e) {
            log.warn("Redis 读 token 失败（按未授权处理）: {}", e.getMessage());
            return Optional.empty();
        }
        if (payload == null) {
            return Optional.empty();
        }
        // payload 格式: userId|role
        String[] parts = payload.split("\\|");
        if (parts.length < 1) {
            return Optional.empty();
        }
        Long userId;
        try {
            userId = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            redisTemplate.delete(TOKEN_KEY_PREFIX + token);
            return Optional.empty();
        }
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            redisTemplate.delete(TOKEN_KEY_PREFIX + token);
            return Optional.empty();
        }
        User user = userOpt.get();
        if (user.getStatus() != null && user.getStatus() == 0) {
            // 账号已被禁用（理论上 disable 时已删 token，此处兜底）
            redisTemplate.delete(TOKEN_KEY_PREFIX + token);
            log.warn("token 对应账号已禁用，强制删除: userId={}", userId);
            return Optional.empty();
        }
        // 刷新 TTL（活跃 token 延长）
        try {
            redisTemplate.expire(TOKEN_KEY_PREFIX + token, TOKEN_TTL);
        } catch (Exception ignored) {
        }
        return Optional.of(user);
    }

    /**
     * 禁用用户：status=0 + 删除该用户全部 token（立即失效）
     */
    public void disableUser(Long userId) {
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            throw new IllegalArgumentException("用户不存在: " + userId);
        }
        User user = userOpt.get();
        user.setStatus((short) 0);
        userRepository.save(user);
        deleteUserTokens(userId);
        log.info("用户已禁用并清除全部 token: userId={} username={}", userId, user.getUsername());
    }

    /**
     * 启用用户：status=1（新登录需重新走 login）
     */
    public void enableUser(Long userId) {
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            throw new IllegalArgumentException("用户不存在: " + userId);
        }
        User user = userOpt.get();
        user.setStatus((short) 1);
        userRepository.save(user);
        log.info("用户已启用: userId={} username={}", userId, user.getUsername());
    }

    private void saveToken(String token, Long userId, String role) {
        try {
            redisTemplate.opsForValue().set(TOKEN_KEY_PREFIX + token,
                    userId + "|" + role, TOKEN_TTL);
        } catch (Exception e) {
            log.error("Redis 保存 token 失败: {}", e.getMessage());
            throw new IllegalStateException("登录失败（缓存不可用）: " + e.getMessage(), e);
        }
    }

    /**
     * 删除指定用户的全部 token（禁用/改角色时立即失效）
     */
    private void deleteUserTokens(Long userId) {
        try {
            java.util.Set<String> keys = redisTemplate.keys(TOKEN_KEY_PREFIX + "*");
            if (keys == null || keys.isEmpty()) {
                return;
            }
            String prefix = userId + "|";
            keys.stream()
                    .filter(k -> {
                        String v = redisTemplate.opsForValue().get(k);
                        return v != null && v.startsWith(prefix);
                    })
                    .forEach(redisTemplate::delete);
        } catch (Exception e) {
            log.warn("清除用户 token 失败（禁用已生效，token 将在 TTL 后自然过期）: {}", e.getMessage());
        }
    }
}
