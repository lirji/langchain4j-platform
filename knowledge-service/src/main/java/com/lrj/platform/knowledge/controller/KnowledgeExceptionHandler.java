package com.lrj.platform.knowledge.controller;

import com.lrj.platform.knowledge.ingest.job.IngestionJobNotFoundException;
import com.lrj.platform.knowledge.ingest.job.IngestionAuthorizationException;
import com.lrj.platform.knowledge.ingest.job.IngestionJobConflictException;
import com.lrj.platform.knowledge.store.DimensionMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;

/**
 * knowledge-service 兜底异常映射：把服务/存储层漏出的未捕获异常统一转成 {@code {error, message}} + 合适的 HTTP
 * 状态，并在服务端记录完整堆栈——避免任何 {@code RuntimeException} 直接变成无日志、无结构的裸 500
 * （历史上删除文档时后端存储报错即如此暴露给前端）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>继承 {@link ResponseEntityExceptionHandler}：保留 Spring 对框架异常的既有映射（请求体解析失败→400、
 *       方法不支持→405 等），不被下面的 {@code Exception} 兜底吞成 500。</li>
 *   <li>显式处理 {@link ResponseStatusException}：controller 主动抛出的 403（scope 不足）等状态语义原样保留。</li>
 *   <li>{@link Order} 置最低优先级：{@code Exception} 兜底最后才匹配，让 enforce 模式下更具体的
 *       {@link AuthzExceptionHandler}（HIGHEST_PRECEDENCE）先接管 {@code AccessDeniedException}。</li>
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class KnowledgeExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeExceptionHandler.class);

    /** 保留 controller 主动抛出的状态语义（如 ingest/public-ingest scope 不足的 403）。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> onStatus(ResponseStatusException ex) {
        String reason = ex.getReason() == null ? ex.getStatusCode().toString() : ex.getReason();
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("error", "request_failed", "message", reason));
    }

    /** 非法入参（如空标题/空文本/坏的 base64 图片）统一 400，而非 500。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> onBadRequest(IllegalArgumentException ex) {
        String message = ex.getMessage() == null ? "bad request" : ex.getMessage();
        return ResponseEntity.badRequest().body(Map.of("error", "bad_request", "message", message));
    }

    @ExceptionHandler(IngestionJobNotFoundException.class)
    public ResponseEntity<Map<String, Object>> onIngestionJobNotFound(
            IngestionJobNotFoundException ex
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "not_found", "message", ex.getMessage()));
    }

    @ExceptionHandler(IngestionAuthorizationException.class)
    public ResponseEntity<Map<String, Object>> onIngestionAuthorization(
            IngestionAuthorizationException ex
    ) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "forbidden", "message", ex.getMessage()));
    }

    @ExceptionHandler(IngestionJobConflictException.class)
    public ResponseEntity<Map<String, Object>> onIngestionConflict(
            IngestionJobConflictException ex
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "conflict", "message", ex.getMessage()));
    }

    /** 向量库维度与当前 embedding 模型不一致（切换 provider/模型后未重建 collection）：409 冲突而非 500。 */
    @ExceptionHandler(DimensionMismatchException.class)
    public ResponseEntity<Map<String, Object>> onDimensionMismatch(DimensionMismatchException ex) {
        log.warn("vector store dimension mismatch (collection={} existing={} incoming={})",
                ex.collection(), ex.existingDimension(), ex.incomingDimension());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "dimension_mismatch", "message", ex.getMessage()));
    }

    /** 兜底：记录完整堆栈供排障，返回结构化 500（不外泄内部异常细节给前端）。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> onUnexpected(Exception ex) {
        log.error("unhandled knowledge-service error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "internal_error", "message", "internal server error"));
    }
}
