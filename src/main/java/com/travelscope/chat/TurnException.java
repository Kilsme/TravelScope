package com.travelscope.chat;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class TurnException extends RuntimeException {
    public final HttpStatus status;
    public final String code;
    public final UUID activeTurnId;

    public TurnException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public TurnException(HttpStatus status, String code, String message, UUID activeTurnId) {
        super(message);
        this.status = status;
        this.code = code;
        this.activeTurnId = activeTurnId;
    }
}
