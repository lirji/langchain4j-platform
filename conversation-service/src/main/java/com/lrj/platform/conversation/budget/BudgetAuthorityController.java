package com.lrj.platform.conversation.budget;

import com.lrj.platform.metering.budget.BudgetLedger;
import com.lrj.platform.protocol.metering.*;
import com.lrj.platform.security.TenantContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Java 承载跨语言预算权威，账本复用平台 Redis；HTTP 只暴露预留与结算两个 RPC。 */
@RestController
@ConditionalOnProperty(name = "app.token-budget.reservations.api-enabled", havingValue = "true")
public final class BudgetAuthorityController {
    private final BudgetLedger ledger;
    public BudgetAuthorityController(BudgetLedger ledger) { this.ledger = ledger; }

    /** 租户/用户从专用令牌取，额度和操作参数须与签名一致。 */
    @PostMapping("/internal/metering/reservations")
    public ResponseEntity<?> reserve(@RequestBody BudgetReservationRequest body, HttpServletRequest request) {
        Claims c = claims(request);
        if (c == null || !matches(c, "operation_id", body.operationId()) || !matches(c, "tokens", body.tokens()))
            return ResponseEntity.status(403).build();
        try {
            var tenant = TenantContext.current();
            var r = ledger.reserve(tenant.tenantId(), tenant.userId(), body.operationId(), body.tokens());
            return ResponseEntity.ok(new BudgetReservationReply(r.operationId(), r.day(), r.reservedTokens()));
        } catch (BudgetLedger.Exceeded e) { return ResponseEntity.status(429).build(); }
        catch (BudgetLedger.Conflict e) { return ResponseEntity.status(409).build(); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().build(); }
        catch (RuntimeException unavailable) { return ResponseEntity.status(503).build(); }
    }

    /** 结算只允许修改原 owner/operation/day 的预留；重复同 usage 幂等。 */
    @PostMapping("/internal/metering/settlements")
    public ResponseEntity<?> settle(@RequestBody BudgetSettlementRequest body, HttpServletRequest request) {
        Claims c = claims(request);
        if (c == null || !matches(c, "operation_id", body.operationId()) || !matches(c, "day", body.day())
                || !matches(c, "reserved_tokens", body.reservedTokens()) || !matches(c, "actual_tokens", body.actualTokens()))
            return ResponseEntity.status(403).build();
        try {
            var tenant = TenantContext.current();
            ledger.settle(new BudgetLedger.Reservation(tenant.tenantId(), tenant.userId(), body.operationId(),
                    body.day(), body.reservedTokens()), body.actualTokens());
            return ResponseEntity.noContent().build();
        } catch (BudgetLedger.Conflict e) { return ResponseEntity.status(409).build(); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().build(); }
        catch (RuntimeException unavailable) { return ResponseEntity.status(503).build(); }
    }

    private Claims claims(HttpServletRequest request) {
        Object value = request.getAttribute(BudgetServiceAuthFilter.CLAIMS_ATTRIBUTE);
        return value instanceof Claims c && TenantContext.current().hasScope("budget.rpc") ? c : null;
    }
    private boolean matches(Claims c, String key, Object expected) {
        Object value = c.get(key);
        if (expected instanceof Long n) return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == n;
        return java.util.Objects.equals(value, expected);
    }
}
