package com.travelscope.controller;

import com.travelscope.chat.*;
import com.travelscope.dto.ChatRequest;
import com.travelscope.entity.User;
import com.travelscope.security.AuthInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

@RestController
@RequestMapping("/chat")
public class ChatTurnController {
    private final ChatTurnService turns;

    public ChatTurnController(ChatTurnService turns) {
        this.turns = turns;
    }

    private long user(HttpServletRequest request) {
        return ((User) request.getAttribute(AuthInterceptor.ATTR_CURRENT_USER)).getId();
    }

    @PostMapping("/turns")
    public ResponseEntity<?> submit(
            @Valid @RequestBody ChatRequest body, HttpServletRequest request) {
        ChatTurn t = turns.submit(user(request), body);
        return ResponseEntity.status(t.active() ? 202 : 200).body(t.view());
    }

    @GetMapping("/turns/{id}")
    public Object get(@PathVariable UUID id, HttpServletRequest request) {
        return turns.get(user(request), id).view();
    }

    @GetMapping(value = "/turns/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter events(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") long afterSeq,
            HttpServletRequest request) {
        return turns.subscribe(user(request), id, afterSeq, false);
    }

    @PostMapping("/turns/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable UUID id, HttpServletRequest request) {
        var t = turns.cancel(user(request), id);
        return ResponseEntity.status(t.active() ? 202 : 200).body(t.view());
    }

    @GetMapping("/conversations/{id}/turns")
    public Object list(
            @PathVariable long id,
            @RequestParam(defaultValue = "false") boolean activeOnly,
            HttpServletRequest request) {
        return turns.list(user(request), id, activeOnly).stream().map(ChatTurn::view).toList();
    }
}
