package com.travelscope.security;

import com.travelscope.entity.User;
import com.travelscope.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AuthInterceptor 单元测试（认证拦截：无头 401 / 禁用 403 语义 / 放行注入 attribute）
 * <p>
 * Fake AuthService（子类覆写 resolveUser）+ MockHttpServletRequest/Response。
 * </p>
 */
class AuthInterceptorTest {

    /** 可控 Fake AuthService（覆写 resolveUser 决定放行/拒绝） */
    static class FakeAuthService extends AuthService {
        Optional<User> nextUser = Optional.empty();

        FakeAuthService() {
            super(null, null);
        }

        @Override
        public Optional<User> resolveUser(String token) {
            return nextUser;
        }
    }

    private FakeAuthService authService;
    private AuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        authService = new FakeAuthService();
        interceptor = new AuthInterceptor(authService);
    }

    private static User user(Long id, String role, short status) {
        User u = new User();
        u.setId(id);
        u.setRole(role);
        u.setStatus(status);
        u.setUsername("u" + id);
        return u;
    }

    @Test
    @DisplayName("检测标准1 逻辑层：无 Authorization 头 → 401 {code,message}")
    void testNoHeader401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean pass = interceptor.preHandle(req, resp, null);

        assertFalse(pass);
        assertEquals(401, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("\"code\":401"));
        assertTrue(resp.getContentAsString().contains("未授权"));
    }

    @Test
    @DisplayName("格式错误的头（非 Bearer）→ 401")
    void testMalformedHeader401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Basic abc");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(req, resp, null));
        assertEquals(401, resp.getStatus());
    }

    @Test
    @DisplayName("无效/禁用 token → 401（resolveUser 返回空）")
    void testInvalidToken401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer invalid-token");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(req, resp, null));
        assertEquals(401, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("未授权或账号已禁用"));
    }

    @Test
    @DisplayName("有效 token → 放行并注入 currentUser/userRole attribute")
    void testValidTokenPasses() throws Exception {
        authService.nextUser = Optional.of(user(1L, "admin", (short) 1));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean pass = interceptor.preHandle(req, resp, null);

        assertTrue(pass);
        assertEquals(200, resp.getStatus(), "放行不应改状态码");
        User injected = (User) req.getAttribute(AuthInterceptor.ATTR_CURRENT_USER);
        assertEquals(1L, injected.getId());
        assertEquals("admin", req.getAttribute(AuthInterceptor.ATTR_USER_ROLE));
    }

    @Test
    @DisplayName("OPTIONS 预检直接放行（不验证 token）")
    void testOptionsPasses() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/api/admin/users");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(req, resp, null));
    }
}
