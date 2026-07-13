package com.lrj.platform.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 统一把 {@link AuthException} 映射为 {@code {error, message}} + 对应 HTTP 状态，覆盖 auth-service
 * 全部 {@code @RestController}（登录与 admin），取代此前 AuthController/AdminController 各自重复的
 * {@code @ExceptionHandler}。
 */
@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, Object>> handle(AuthException ex) {
        return ResponseEntity.status(ex.status())
                .body(Map.of("error", ex.code(), "message", ex.getMessage()));
    }
}
