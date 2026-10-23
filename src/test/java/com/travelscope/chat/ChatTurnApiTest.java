package com.travelscope.chat;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.travelscope.common.GlobalExceptionHandler;
import com.travelscope.controller.AuthController;
import com.travelscope.controller.ChatTurnController;
import com.travelscope.entity.User;
import com.travelscope.security.AuthInterceptor;
import com.travelscope.service.AuthService;

import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.*;

class ChatTurnApiTest {
    MockMvc mvc;
    ChatTurnService turns;
    UUID id = UUID.randomUUID();

    @BeforeEach
    void setup() {
        turns = mock(ChatTurnService.class);
        var auth = mock(AuthService.class);
        var user = new User();
        user.setId(71L);
        user.setRole("user");
        user.setNickname("测试账户");
        when(auth.resolveUser("fixture")).thenReturn(Optional.of(user));
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new ChatTurnController(turns), new AuthController(auth))
                        .addInterceptors(new AuthInterceptor(auth))
                        .setControllerAdvice(
                                new GlobalExceptionHandler(), new TurnExceptionHandler())
                        .build();
    }

    @Test
    void allTaskRoutesRequireBearerIdentity() throws Exception {
        mvc.perform(get("/chat/turns/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/chat/turns/" + id + "/events")).andExpect(status().isUnauthorized());
        mvc.perform(post("/chat/turns/" + id + "/cancel")).andExpect(status().isUnauthorized());
        mvc.perform(get("/chat/conversations/123/turns")).andExpect(status().isUnauthorized());
        mvc.perform(post("/chat/turns").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(turns);
    }

    @Test
    void missingAndUnownedTasksUseIdentical404Body() throws Exception {
        when(turns.get(eq(71L), any()))
                .thenThrow(new TurnException(HttpStatus.NOT_FOUND, "TURN_NOT_FOUND", "任务不存在"));
        mvc.perform(get("/chat/turns/" + id).header("Authorization", "Bearer fixture"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TURN_NOT_FOUND"))
                .andExpect(jsonPath("$.snapshot").doesNotExist());
    }

    @Test
    void conflictAndExpiredReplayCodesSurviveGlobalExceptionHandler() throws Exception {
        when(turns.submit(eq(71L), any()))
                .thenThrow(new TurnException(HttpStatus.CONFLICT, "CONVERSATION_BUSY", "会话忙", id));
        mvc.perform(
                        post("/chat/turns")
                                .header("Authorization", "Bearer fixture")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"turnId\":\""
                                                + UUID.randomUUID()
                                                + "\",\"message\":\"你好\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONVERSATION_BUSY"))
                .andExpect(jsonPath("$.activeTurnId").value(id.toString()));
        when(turns.subscribe(71, id, 0, false))
                .thenThrow(new TurnException(HttpStatus.GONE, "REPLAY_EXPIRED", "过期"));
        mvc.perform(get("/chat/turns/" + id + "/events").header("Authorization", "Bearer fixture"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("REPLAY_EXPIRED"));
    }

    @Test
    void malformedUuidAndInvalidJsonReturn400() throws Exception {
        mvc.perform(get("/chat/turns/invalid").header("Authorization", "Bearer fixture"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/chat/turns")
                                .header("Authorization", "Bearer fixture")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{bad"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void identityComesFromServerAndContainsNoToken() throws Exception {
        mvc.perform(get("/auth/me").header("Authorization", "Bearer fixture"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(71))
                .andExpect(jsonPath("$.token").doesNotExist());
    }
}
