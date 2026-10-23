package com.travelscope.chat;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;

@RestControllerAdvice
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class TurnExceptionHandler {
    @ExceptionHandler(TurnException.class)
    public ResponseEntity<?> handle(TurnException error) {
        var body = new LinkedHashMap<String, Object>();
        body.put("code", error.code);
        body.put("message", error.getMessage());
        if (error.activeTurnId != null) body.put("activeTurnId", error.activeTurnId);
        var response = ResponseEntity.status(error.status);
        if (error.status.value() == 429) response.header("Retry-After", "5");
        return response.body(body);
    }

    @ExceptionHandler({
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<?> invalidRequest(Exception error) {
        return ResponseEntity.badRequest()
                .body(java.util.Map.of("code", "INVALID_REQUEST", "message", "请求格式或轮次标识不合法"));
    }
}
