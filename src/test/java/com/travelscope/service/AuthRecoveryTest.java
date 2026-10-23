package com.travelscope.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.travelscope.config.AppProperties;
import com.travelscope.entity.User;
import com.travelscope.repository.UserRepository;
import com.travelscope.security.AuthInterceptor;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

class AuthRecoveryTest {
    AuthService auth;
    UserRepository users;
    StringRedisTemplate redis;
    ValueOperations<String, String> values;
    User user;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        users = mock(UserRepository.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        user = new User();
        user.setId(1L);
        user.setRole("user");
        user.setStatus((short) 1);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(values.get("auth:token:verified")).thenReturn("1|user");
        auth =
                new AuthService(
                        users, redis, mock(InvitationCodeService.class), new AppProperties());
    }

    @Test
    void verifiedSessionKeepsWorkingDuringRedisFailureAndStillChecksDisabledState() {
        assertThat(auth.resolveUser("verified")).isPresent();
        when(values.get(anyString())).thenThrow(new IllegalStateException("redis offline"));
        assertThat(auth.resolveUser("verified")).isPresent();
        user.setStatus((short) 0);
        assertThat(auth.resolveUser("verified")).isEmpty();
        user.setStatus((short) 1);
        assertThat(auth.resolveUser("verified")).isEmpty();
    }

    @Test
    void unknownIdentityGets503RatherThan401AndCannotUseFallback() throws Exception {
        when(values.get(anyString())).thenThrow(new IllegalStateException("redis offline"));
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer unknown");
        var response = new MockHttpServletResponse();
        assertThat(new AuthInterceptor(auth).preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
    }

    @Test
    void logoutRevokesFallbackEvenIfRedisDeleteFailsAndDoesNotReviveOnRedisReturn() {
        auth.resolveUser("verified");
        when(redis.delete(anyString())).thenThrow(new IllegalStateException("offline"));
        auth.logout("verified");
        assertThat(auth.resolveUser("verified")).isEmpty();
    }

    @Test
    void confirmedRedisRevocationNeverUsesCachedSession() {
        auth.resolveUser("verified");
        when(values.get(anyString())).thenReturn(null);
        assertThat(auth.resolveUser("verified")).isEmpty();
        when(values.get(anyString())).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> auth.resolveUser("verified"))
                .isInstanceOf(AuthService.AuthUnavailable.class);
    }

    @Test
    void disableRevokesKnownSessionsBeforeRedisCleanup() {
        auth.resolveUser("verified");
        when(redis.keys(anyString())).thenThrow(new IllegalStateException("offline"));
        auth.disableUser(1L);
        user.setStatus((short) 1);
        assertThat(auth.resolveUser("verified")).isEmpty();
    }
}
