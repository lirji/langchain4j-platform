package com.lrj.platform.knowledge.controller;

import com.lrj.authz.sdk.AccessDeniedException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 把 SDK {@code @CheckAccess} 判权失败的 {@link AccessDeniedException} 转成 403（enforce 专用）。
 * 仅在 enforce 注册，避免影响 disabled/shadow 的现有错误契约。
 *
 * <p>{@link Order} 置最高优先级：{@code AccessDeniedException} 也是 {@code Exception}，必须先于
 * {@link KnowledgeExceptionHandler}（LOWEST_PRECEDENCE 的 {@code Exception} 兜底）匹配，否则会被兜底吞成 500。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "app.rag.authz.mode", havingValue = "enforce")
public class AuthzExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> onDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "forbidden", "message", "access denied"));
    }
}
