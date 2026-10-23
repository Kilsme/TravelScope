package com.travelscope.service;

import com.travelscope.entity.Conversation;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

/** Existing algorithm tests exercise the extracted execution, without a database or paid API. */
final class ChatExecutionTestSupport {
    static SseEmitter stream(
            ChatService service, Conversation conversation, Long user, String text) {
        return stream(service, conversation, user, text, null);
    }

    static SseEmitter stream(
            ChatService service, Conversation conversation, Long user, String text, String image) {
        SseEmitter emitter = new SseEmitter(30_000L);
        var execution =
                service.execution(conversation, user, text, image, UUID.randomUUID(), cost -> true);
        execution.events.subscribe(
                e -> send(emitter, e.getType(), e.getPayload()),
                error -> {
                    send(emitter, "error", service.executionError(error));
                    emitter.complete();
                },
                () -> {
                    send(emitter, "done", service.executionResult(execution).content());
                    emitter.complete();
                });
        return emitter;
    }

    private static void send(SseEmitter emitter, String type, String data) {
        try {
            emitter.send(SseEmitter.event().name(type).data(data));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
