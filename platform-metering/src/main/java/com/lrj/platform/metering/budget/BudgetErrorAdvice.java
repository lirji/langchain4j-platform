package com.lrj.platform.metering.budget;

import com.lrj.platform.metering.TokenBudgetTracker;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

/** 准入异常映射稳定 HTTP 错误，不向用户暴露 Redis 异常或内部堆栈。 */
@RestControllerAdvice
public final class BudgetErrorAdvice {
    private final TokenBudgetTracker tracker;
    public BudgetErrorAdvice(TokenBudgetTracker tracker) { this.tracker = tracker; }

    /** 日额度不足等待下一日，重试不能产生新的模型消耗。 */
    @ExceptionHandler(BudgetLedger.Exceeded.class)
    public ResponseEntity<?> exceeded() {
        return ResponseEntity.status(429).header("Retry-After", Long.toString(tracker.secondsUntilReset()))
                .body(Map.of("error", "TENANT_TOKEN_BUDGET_EXCEEDED"));
    }

    /** 未决预留继续保留，恢复后重新准入；不将账本故障当作零消耗。 */
    @ExceptionHandler(BudgetLedger.Unavailable.class)
    public ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).header("Retry-After", "5").body(Map.of("error", "TOKEN_BUDGET_UNAVAILABLE"));
    }

    /** 同操作异参数、重复模型执行都属于冲突。 */
    @ExceptionHandler(BudgetLedger.Conflict.class)
    public ResponseEntity<?> conflict() {
        return ResponseEntity.status(409).body(Map.of("error", "TOKEN_BUDGET_OPERATION_CONFLICT"));
    }
}
