package com.travelscope.service;

import com.travelscope.entity.User;
import com.travelscope.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AuthService 单元测试（认证：登录/Redis token/禁用删token，FR-A01~A03 检测标准 1/3 逻辑层）
 * <p>
 * Fake StringRedisTemplate（内存 Map 模拟 opsForValue/get/delete/keys/expire）+
 * Fake UserRepository（内存用户表）。
 * </p>
 */
class AuthServiceTest {

    /** 内存版 StringRedisTemplate（ValueOperations 是接口，用动态代理只实现 AuthService 用到的方法） */
    static class FakeRedisTemplate extends StringRedisTemplate {
        final Map<String, String> store = new HashMap<>();

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public org.springframework.data.redis.core.ValueOperations<String, String> opsForValue() {
            return (org.springframework.data.redis.core.ValueOperations<String, String>)
                    java.lang.reflect.Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[]{org.springframework.data.redis.core.ValueOperations.class},
                            (proxy, method, args) -> switch (method.getName()) {
                                case "set" -> {
                                    store.put((String) args[0], (String) args[1]);
                                    yield null;
                                }
                                case "get" -> store.get(String.valueOf(args[0]));
                                default -> throw new UnsupportedOperationException(method.getName());
                            });
        }

        @Override
        public Boolean delete(String key) {
            return store.remove(key) != null;
        }

        @Override
        public java.util.Set<String> keys(String pattern) {
            String prefix = pattern.replace("*", "");
            java.util.Set<String> result = new java.util.HashSet<>();
            for (String k : store.keySet()) {
                if (k.startsWith(prefix)) result.add(k);
            }
            return result;
        }

        @Override
        public Boolean expire(String key, java.time.Duration timeout) {
            return store.containsKey(key);
        }
    }

    /** 内存用户表 */
    static class FakeUserRepository {
        final Map<String, User> byUsername = new HashMap<>();
        final Map<Long, User> byId = new HashMap<>();

        Optional<User> findByUsername(String username) {
            return Optional.ofNullable(byUsername.get(username));
        }

        Optional<User> findById(Long id) {
            return Optional.ofNullable(byId.get(id));
        }

        User save(User u) {
            if (u.getId() == null) {
                u.setId((long) (byId.size() + 10));   // 注册路径：分配 id
            }
            byUsername.put(u.getUsername(), u);
            byId.put(u.getId(), u);
            return u;
        }
    }

    /** 内存邀请码表 */
    static class FakeInvitationCodeRepository {
        final Map<String, com.travelscope.entity.InvitationCode> byCode = new HashMap<>();

        Optional<com.travelscope.entity.InvitationCode> findByCode(String code) {
            return Optional.ofNullable(byCode.get(code));
        }

        com.travelscope.entity.InvitationCode save(com.travelscope.entity.InvitationCode ic) {
            if (ic.getId() == null) {
                ic.setId((long) (byCode.size() + 1));
            }
            byCode.put(ic.getCode(), ic);
            return ic;
        }
    }

    private FakeRedisTemplate redis;
    private FakeUserRepository userRepo;
    private FakeInvitationCodeRepository icRepo;
    private AuthService authService;
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private static User makeUser(Long id, String username, String rawPassword, short status, String role) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setPassword(ENCODER.encode(rawPassword));
        u.setStatus(status);
        u.setRole(role);
        return u;
    }

    @BeforeEach
    void setUp() {
        redis = new FakeRedisTemplate();
        userRepo = new FakeUserRepository();
        icRepo = new FakeInvitationCodeRepository();
        // AuthService 构造需要 UserRepository 接口——用动态代理只实现用到的方法
        UserRepository repo = (UserRepository) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{UserRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByUsername" -> userRepo.findByUsername((String) args[0]);
                    case "findById" -> userRepo.findById((Long) args[0]);
                    case "save" -> userRepo.save((User) args[0]);
                    case "saveAndFlush" -> userRepo.save((User) args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        com.travelscope.repository.InvitationCodeRepository icProxy =
                (com.travelscope.repository.InvitationCodeRepository) java.lang.reflect.Proxy
                        .newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[]{com.travelscope.repository.InvitationCodeRepository.class},
                                (proxy, method, args) -> switch (method.getName()) {
                                    case "findByCode" -> icRepo.findByCode((String) args[0]);
                                    case "save" -> icRepo.save((com.travelscope.entity.InvitationCode) args[0]);
                                    default -> throw new UnsupportedOperationException(method.getName());
                                });
        authService = new AuthService(repo, redis, new InvitationCodeService(icProxy),
                new com.travelscope.config.AppProperties());
        userRepo.save(makeUser(1L, "admin", "admin123", (short) 1, "admin"));
        userRepo.save(makeUser(2L, "user1", "pass1", (short) 1, "user"));
        userRepo.save(makeUser(3L, "banned", "pass3", (short) 0, "user"));
    }

    @Test
    @DisplayName("登录成功：admin/admin123 颁发 token 且可解析回用户")
    void testLoginSuccess() {
        String token = authService.login("admin", "admin123");
        assertTrue(token != null && !token.isBlank(), "应颁发 token");
        assertTrue(redis.store.containsKey("auth:token:" + token), "token 应存 Redis");

        Optional<User> user = authService.resolveUser(token);
        assertTrue(user.isPresent());
        assertEquals(1L, user.get().getId());
        assertEquals("admin", user.get().getRole());
    }

    @Test
    @DisplayName("密码错误 / 用户不存在 / 账号禁用 → 登录失败返回 null")
    void testLoginFailures() {
        assertNull(authService.login("admin", "wrong-pass"), "密码错应失败");
        assertNull(authService.login("no-such-user", "any"), "用户不存在应失败");
        assertNull(authService.login("banned", "pass3"), "禁用账号应失败");
    }

    @Test
    @DisplayName("检测标准3 逻辑层：禁用用户 → status=0 且其 token 全部删除（立即拒绝）")
    void testDisableUserDeletesTokens() {
        String t1 = authService.login("user1", "pass1");
        String t2 = authService.login("user1", "pass1");
        assertTrue(t1 != null && t2 != null);

        authService.disableUser(2L);

        assertEquals((short) 0, userRepo.byId.get(2L).getStatus());
        assertTrue(authService.resolveUser(t1).isEmpty(), "禁用后 t1 应立即拒绝");
        assertTrue(authService.resolveUser(t2).isEmpty(), "禁用后 t2 应立即拒绝");
    }

    @Test
    @DisplayName("无效/过期 token → 解析为空；禁用的账号 token 解析时强制删除")
    void testResolveEdgeCases() {
        assertTrue(authService.resolveUser(null).isEmpty());
        assertTrue(authService.resolveUser("").isEmpty());
        assertTrue(authService.resolveUser("no-such-token").isEmpty());

        // 先登录拿到 token，再把用户 status 置 0（绕过 disableUser 的删 token，模拟遗留 token）
        String token = authService.login("user1", "pass1");
        assertTrue(token != null);
        userRepo.byId.get(2L).setStatus((short) 0);
        assertTrue(authService.resolveUser(token).isEmpty(), "禁用账号 token 应被拒");
        assertFalse(redis.store.containsKey("auth:token:" + token), "禁用 token 应被强制删除");
    }

    @Test
    @DisplayName("logout 删除 token；启用后需重新登录")
    void testLogoutAndEnable() {
        String token = authService.login("user1", "pass1");
        assertTrue(token != null);
        authService.logout(token);
        assertTrue(authService.resolveUser(token).isEmpty());

        authService.disableUser(2L);
        authService.enableUser(2L);
        assertEquals((short) 1, userRepo.byId.get(2L).getStatus());
        assertNull(authService.resolveUser(token).orElse(null), "启用后旧 token 不再有效（需重新登录）");
    }

    // ==================== 注册（2026-10-11 邀请码准入） ====================

    private com.travelscope.entity.InvitationCode seedCode(String code) {
        com.travelscope.entity.InvitationCode ic = new com.travelscope.entity.InvitationCode();
        ic.setCode(code);
        ic.setCreatedBy(1L);
        return icRepo.save(ic);
    }

    @Test
    @DisplayName("注册成功：有效邀请码 → 用户落库（role=user、初始积分 500），码被消费")
    void testRegisterSuccess() {
        seedCode("ABCD2345");

        User created = authService.register("newbie", "pass123", "新人", "ABCD2345");

        assertEquals("newbie", created.getUsername());
        assertEquals("user", created.getRole());
        assertEquals(500L, created.getPointsBalance(), "初始积分应取 points.initial-balance 默认 500");
        assertTrue(icRepo.byCode.get("ABCD2345").getUsedBy() != null, "邀请码应被消费");
        assertTrue(ENCODER.matches("pass123", created.getPassword()), "密码应 BCrypt 加密落库");
    }

    @Test
    @DisplayName("注册拒绝：邀请码无效/已使用、用户名冲突 → 均不落库")
    void testRegisterRejected() {
        seedCode("USED9999");
        seedCode("OKOKOK22");

        // 无效码
        try {
            authService.register("u-x", "p", null, "NOEXIST1");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        assertTrue(userRepo.byUsername.get("u-x") == null, "无效码时用户不应落库");

        // 已使用码：先成功注册一个，再复用同码
        authService.register("u-y", "p", null, "USED9999");
        try {
            authService.register("u-z", "p", null, "USED9999");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        assertTrue(userRepo.byUsername.get("u-z") == null, "已使用码时用户不应落库");

        // 用户名冲突（码有效也拒）
        try {
            authService.register("user1", "p", null, "OKOKOK22");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        assertTrue(icRepo.byCode.get("OKOKOK22").getUsedBy() == null, "用户名冲突时码不应被消费");
    }
}
