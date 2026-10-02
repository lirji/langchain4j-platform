package com.lrj.platform.conversation.budget;

import com.lrj.platform.metering.InMemoryTokenBudgetTracker;
import com.lrj.platform.metering.TokenBudgetProperties;
import com.lrj.platform.metering.budget.InMemoryBudgetLedger;
import com.lrj.platform.protocol.metering.BudgetReservationRequest;
import com.lrj.platform.protocol.metering.BudgetReservationReply;
import com.lrj.platform.protocol.metering.BudgetSettlementRequest;
import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.InternalTokenAuthFilter;
import com.lrj.platform.security.TenantContext;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class BudgetAuthorityControllerTest {
    private static final String SECRET = "budget-purpose-key-separated-at-least-32-bytes";
    private final TokenBudgetProperties props = props();
    private final InMemoryTokenBudgetTracker tracker = new InMemoryTokenBudgetTracker(props);
    private final BudgetAuthorityController controller = new BudgetAuthorityController(new InMemoryBudgetLedger(tracker, props, Clock.systemUTC()));
    private TokenBudgetProperties props() {
        var p = new TokenBudgetProperties(); p.setTimezone("UTC"); p.getDailyTokens().setDefault(1000); return p;
    }

    private String token(String tenant, String user, String action, Map<String, Object> parameters) {
        Instant now = Instant.now();
        return Jwts.builder().header().keyId("metering-v1").type("JWT").and()
                .issuer("agentscope-platform").subject("agentscope-platform").audience().add("platform-metering").and()
                .claim("tenant", tenant).claim("actor_uid", user).claim("token_use", "tenant_budget").claim("act", action)
                .claims(parameters).id(UUID.randomUUID().toString()).issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(30))).signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private ResponseEntity<?> invoke(String token, String action, Object body) throws Exception {
        var request = new MockHttpServletRequest("POST", "/internal/metering/" + (action.equals("reserve") ? "reservations" : "settlements"));
        request.addHeader("Authorization", "Bearer " + token);
        var response = new MockHttpServletResponse();
        var result = new AtomicReference<ResponseEntity<?>>();
        var internal = new InternalTokenAuthFilter(new InternalToken("internal-purpose-key-separated-at-least-32-bytes", Duration.ofMinutes(5)),
                new InternalSecurityProperties(), false);
        new BudgetServiceAuthFilter(new BudgetServiceTokens(SECRET)).doFilter(request, response, (r, s) ->
                internal.doFilter(r, s, (rr, ss) -> result.set(action.equals("reserve")
                        ? controller.reserve((BudgetReservationRequest) body, request)
                        : controller.settle((BudgetSettlementRequest) body, request))));
        assertThat(TenantContext.captureRaw()).isNull();
        return result.get() == null ? ResponseEntity.status(response.getStatus()).build() : result.get();
    }

    @Test void verifiedIdentitySharesBudgetAndSettlementIsIdempotent() throws Exception {
        var claims = Map.<String, Object>of("operation_id", "python", "tokens", 1000);
        var reserved = invoke(token("t1", "u1", "reserve", claims), "reserve", new BudgetReservationRequest("python", 1000));
        assertThat(reserved.getStatusCode().value()).isEqualTo(200);
        var r = (BudgetReservationReply) reserved.getBody();
        var second = invoke(token("t1", "u2", "reserve", Map.of("operation_id", "other", "tokens", 1)),
                "reserve", new BudgetReservationRequest("other", 1));
        assertThat(second.getStatusCode().value()).isEqualTo(429);
        var usage = new BudgetSettlementRequest("python", r.day(), 1000, 300);
        var proof = Map.<String, Object>of("operation_id", "python", "day", r.day(), "reserved_tokens", 1000, "actual_tokens", 300);
        String settlement = token("t1", "u1", "settle", proof);
        assertThat(invoke(settlement, "settle", usage).getStatusCode().value()).isEqualTo(204);
        assertThat(invoke(settlement, "settle", usage).getStatusCode().value()).isEqualTo(204);
        assertThat(tracker.currentUsed("t1")).isEqualTo(300);
    }

    @Test void normalAccessOrWrongPurposeCannotSettleAndParametersCannotBeRewritten() throws Exception {
        assertThat(invoke("user-jwt", "reserve", new BudgetReservationRequest("op", 1)).getStatusCode().value()).isEqualTo(401);
        String proof = token("t1", "u1", "reserve", Map.of("operation_id", "op", "tokens", 100));
        assertThat(invoke(proof, "reserve", new BudgetReservationRequest("other", 100)).getStatusCode().value()).isEqualTo(403);
        assertThat(invoke(proof, "reserve", new BudgetReservationRequest("op", 1)).getStatusCode().value()).isEqualTo(403);
        assertThat(invoke(proof, "settle", new BudgetSettlementRequest("op", "2026-10-01", 100, 0)).getStatusCode().value()).isEqualTo(401);
    }

    @Test void otherUserOrTenantCannotReleaseOriginalReservation() throws Exception {
        var reserved = invoke(token("t1", "u1", "reserve", Map.of("operation_id", "op", "tokens", 1000)),
                "reserve", new BudgetReservationRequest("op", 1000));
        var r = (BudgetReservationReply) reserved.getBody();
        var proof = Map.<String, Object>of("operation_id", "op", "day", r.day(), "reserved_tokens", 1000, "actual_tokens", 0);
        var body = new BudgetSettlementRequest("op", r.day(), 1000, 0);
        assertThat(invoke(token("t1", "u2", "settle", proof), "settle", body).getStatusCode().value()).isEqualTo(409);
        assertThat(invoke(token("t2", "u1", "settle", proof), "settle", body).getStatusCode().value()).isEqualTo(409);
    }
}
