package com.travelscope.chat;

import java.time.Instant;
import java.util.*;

public record ChatTurn(
        long userId,
        UUID turnId,
        long conversationId,
        String requestHash,
        String locale,
        String responseLocale,
        int requestHashVersion,
        String inputText,
        UUID sourceTurnId,
        String imageKey,
        String imageType,
        String status,
        Long userMessageId,
        Long assistantMessageId,
        long lastSeq,
        long snapshotSeq,
        Map<String, Object> snapshot,
        String outcome,
        String errorCode,
        String errorMessage,
        boolean eventsExpired,
        Instant createdAt,
        Instant updatedAt) {
    public static final Set<String> ACTIVE = Set.of("PREPARING", "QUEUED", "RUNNING", "CANCELLING");

    public boolean active() {
        return ACTIVE.contains(status);
    }

    public Map<String, Object> view() {
        var v = new LinkedHashMap<String, Object>();
        v.put("turnId", turnId);
        v.put("conversationId", conversationId);
        v.put("status", status);
        v.put("locale", locale);
        v.put("responseLocale", responseLocale);
        v.put("userMessageId", userMessageId);
        v.put("assistantMessageId", assistantMessageId);
        v.put("lastSeq", lastSeq);
        v.put("snapshotSeq", snapshotSeq);
        v.put("snapshot", snapshot);
        v.put("outcome", outcome);
        v.put("errorCode", errorCode);
        v.put("errorMessage", errorMessage);
        v.put("inputText", inputText);
        v.put("createdAt", createdAt.toString());
        v.put(
                "image",
                imageKey != null && userMessageId != null
                        ? "/api/chat/images/"
                                + userMessageId
                                + "/"
                                + imageKey.substring(imageKey.lastIndexOf('/') + 1)
                        : null);
        v.put("eventsUrl", "/api/chat/turns/" + turnId + "/events");
        return v;
    }
}
