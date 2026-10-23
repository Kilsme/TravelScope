package com.travelscope.service;

import com.travelscope.config.AppProperties;
import com.travelscope.entity.User;
import com.travelscope.repository.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 认证服务（管理端 /admin/** + 全站禁用体系，FR-A01~A03 配套）
 *
 * <p><b>Redis Token 模式</b>（用户决策 2026-09-19）：登录校验 users 表 BCrypt 密码 → 生成 UUID token 存 Redis（key
 * {@code auth:token:{token}}，value 序列化 userId|role， TTL 24h）。优点：禁用用户即删全部 token 立即生效（检测标准 3
 * 的「禁用即拒」）； 与项目 Redis 缓存/降级风格一致；零外部依赖（不引入 JJWT）。
 *
 * <p>用户状态机：users.status（0=禁用 / 1=启用）；role（user / admin）。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String TOKEN_KEY_PREFIX = "auth:token:";
    private static final Duration TOKEN_TTL = Duration.ofHours(24);

    // Single-instance degradation: only fingerprints of previously verified sessions are cached.
    // PostgreSQL still checks account status on EVERY request. Unknown tokens never bypass Redis.
    private record VerifiedSession(long userId, long expiresAt, boolean revoked) {}

    private final java.util.concurrent.ConcurrentMap<String, VerifiedSession> verifiedSessions =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static final class AuthUnavailable extends RuntimeException {
        public AuthUnavailable() {
            super("暂时无法确认登录状态，请稍后重试");
        }
    }

    private static String fingerprint(String token) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            token.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void remember(String key, long user) {
        if (verifiedSessions.size() >= 10000)
            verifiedSessions
                    .entrySet()
                    .removeIf(e -> e.getValue().expiresAt() < System.currentTimeMillis());
        if (verifiedSessions.size() < 10000 || verifiedSessions.containsKey(key))
            verifiedSessions.compute(
                    key,
                    (k, old) ->
                            old != null
                                            && old.revoked()
                                            && old.expiresAt() > System.currentTimeMillis()
                                    ? old
                                    : new VerifiedSession(
                                            user,
                                            System.currentTimeMillis() + TOKEN_TTL.toMillis(),
                                            false));
    }

    private void revoke(String token) {
        String key = fingerprint(token);
        verifiedSessions.compute(
                key,
                (k, old) ->
                        new VerifiedSession(
                                old == null ? -1 : old.userId(),
                                System.currentTimeMillis() + TOKEN_TTL.toMillis(),
                                true));
    }

    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final InvitationCodeService invitationCodeService;
    private final AppProperties appProperties;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(
            UserRepository userRepository,
            StringRedisTemplate redisTemplate,
            InvitationCodeService invitationCodeService,
            AppProperties appProperties) {
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplate;
        this.invitationCodeService = invitationCodeService;
        this.appProperties = appProperties;
    }

    /**
     * 注册（2026-10-11 邀请码准入）：仅凭有效邀请码开放。 单事务：用户名唯一校验 → 创建用户（role=user，初始积分 points.initial-balance） →
     * 消费邀请码；任一步失败整体回滚（码无效则用户不落库）。
     *
     * @return 新用户；失败抛 IllegalArgumentException（控制器转 400）
     */
    @Transactional
    public User register(String username, String password, String nickname, String invitationCode) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("用户名与密码不能为空");
        }
        // 先验码（无效/已用直接拒，不建用户）；consume 在落库后复查（权威判定）
        invitationCodeService.validateUsable(invitationCode);
        username = username.trim();
        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("用户名已被占用");
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(nickname != null && !nickname.isBlank() ? nickname.trim() : username);
        user.setRole("user");
        user.setStatus((short) 1);
        user.setPointsBalance(appProperties.getPoints().getInitialBalance());
        User saved = userRepository.saveAndFlush(user);
        // 邀请码消费在同事务：极端并发下码被抢用 → 抛异常回滚（用户不落库）
        invitationCodeService.consume(invitationCode, saved.getId());
        log.info(
                "注册成功: {} userId={}（初始积分 {}）",
                username,
                saved.getId(),
                appProperties.getPoints().getInitialBalance());
        return saved;
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

    /** 登出：删除 token */
    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            revoke(token);
            try {
                redisTemplate.delete(TOKEN_KEY_PREFIX + token);
            } catch (Exception e) {
                log.warn("Redis 登出暂不可用，本实例已撤销该会话");
            }
        }
    }

    /**
     * 解析 token → 用户（校验启用状态；禁用时删键返回 empty）
     *
     * <p>AuthInterceptor 调用点：每次请求都走这里——禁用在 Redis 无 token 时立即被拒， 即使原 token 刚签发不久（disable 时已全删该用户的
     * token）。
     */
    public Optional<User> resolveUser(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String key = fingerprint(token);
        VerifiedSession cached = verifiedSessions.get(key);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis() && cached.revoked())
            return Optional.empty();
        boolean fallback = false;
        String payload;
        try {
            payload = redisTemplate.opsForValue().get(TOKEN_KEY_PREFIX + token);
        } catch (Exception e) {
            if (cached == null
                    || cached.expiresAt() <= System.currentTimeMillis()
                    || cached.revoked()) throw new AuthUnavailable();
            payload = cached.userId() + "|";
            fallback = true;
        }
        if (payload == null) {
            verifiedSessions.remove(key);
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
            revoke(token);
            try {
                redisTemplate.delete(TOKEN_KEY_PREFIX + token);
            } catch (Exception ignored) {
            }
            return Optional.empty();
        }
        User user = userOpt.get();
        if (user.getStatus() != null && user.getStatus() == 0) {
            // 账号已被禁用（理论上 disable 时已删 token，此处兜底）
            revoke(token);
            try {
                redisTemplate.delete(TOKEN_KEY_PREFIX + token);
            } catch (Exception ignored) {
            }
            log.warn("token 对应账号已禁用，强制删除: userId={}", userId);
            return Optional.empty();
        }
        if (!fallback) remember(key, userId);
        // Only Redis-confirmed sessions refresh the fallback TTL.
        // 刷新 TTL（活跃 token 延长）
        if (!fallback) {
            try {
                redisTemplate.expire(TOKEN_KEY_PREFIX + token, TOKEN_TTL);
            } catch (Exception ignored) {
            }
        }
        return Optional.of(user);
    }

    /** 禁用用户：status=0 + 删除该用户全部 token（立即失效） */
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

    /** 启用用户：status=1（新登录需重新走 login） */
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
            redisTemplate
                    .opsForValue()
                    .set(TOKEN_KEY_PREFIX + token, userId + "|" + role, TOKEN_TTL);
        } catch (Exception e) {
            log.error("Redis 保存 token 失败: {}", e.getMessage());
            throw new IllegalStateException("登录失败（缓存不可用）: " + e.getMessage(), e);
        }
    }

    /** 删除指定用户的全部 token（禁用/改角色时立即失效） */
    private void deleteUserTokens(Long userId) {
        verifiedSessions.replaceAll(
                (key, value) ->
                        value.userId() == userId
                                ? new VerifiedSession(
                                        userId,
                                        System.currentTimeMillis() + TOKEN_TTL.toMillis(),
                                        true)
                                : value);
        try {
            java.util.Set<String> keys = redisTemplate.keys(TOKEN_KEY_PREFIX + "*");
            if (keys == null || keys.isEmpty()) {
                return;
            }
            String prefix = userId + "|";
            keys.stream()
                    .filter(
                            k -> {
                                String v = redisTemplate.opsForValue().get(k);
                                return v != null && v.startsWith(prefix);
                            })
                    .forEach(redisTemplate::delete);
        } catch (Exception e) {
            log.warn("清除用户 token 失败（禁用已生效，token 将在 TTL 后自然过期）: {}", e.getMessage());
        }
    }
}
