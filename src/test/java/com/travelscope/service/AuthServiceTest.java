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
            byUsername.put(u.getUsername(), u);
            byId.put(u.getId(), u);
            return u;
        }
    }

    private FakeRedisTemplate redis;
    private FakeUserRepository userRepo;
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
        // AuthService 构造需要 UserRepository 接口——用动态代理只实现用到的两个方法
        UserRepository repo = (UserRepository) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{UserRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByUsername" -> userRepo.findByUsername((String) args[0]);
                    case "findById" -> userRepo.findById((Long) args[0]);
                    case "save" -> userRepo.save((User) args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        authService = new AuthService(repo, redis);
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
}
