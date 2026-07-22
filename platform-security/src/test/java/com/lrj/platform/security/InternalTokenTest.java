package com.lrj.platform.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InternalTokenTest：验证 {@link InternalToken} 的内部 JWT 签发/校验——mint 后 verify 能完整回还
 * 租户身份与 scope，且对错误签名、已过期、null/空白令牌一律返回 null（校验失败）。
 */
class InternalTokenTest {

    private static final String SECRET = "test-secret-at-least-32-bytes-long-000";

    @Test
    void mintThenVerify_roundTripsTenant() {
        InternalToken t = new InternalToken(SECRET, Duration.ofMinutes(5));
        TenantContext.Tenant in = new TenantContext.Tenant("acme", "alice", Set.of("approve", "read"));

        TenantContext.Tenant out = t.verify(t.mint(in));

        assertEquals("acme", out.tenantId());
        assertEquals("alice", out.userId());
        assertTrue(out.hasScope("approve"));
        assertTrue(out.hasScope("read"));
    }

    @Test
    void verify_wrongSignature_returnsNull() {
        InternalToken minter = new InternalToken(SECRET, Duration.ofMinutes(5));
        InternalToken verifier = new InternalToken("a-totally-different-secret-32-bytes-min", Duration.ofMinutes(5));

        String jwt = minter.mint(new TenantContext.Tenant("acme", "alice", Set.of()));

        assertNull(verifier.verify(jwt));
    }

    @Test
    void verify_expiredToken_returnsNull() {
        InternalToken t = new InternalToken(SECRET, Duration.ofSeconds(-1)); // 已过期
        String jwt = t.mint(new TenantContext.Tenant("acme", "alice", Set.of()));
        assertNull(t.verify(jwt));
    }

    @Test
    void verify_nullOrBlank_returnsNull() {
        InternalToken t = new InternalToken(SECRET, Duration.ofMinutes(5));
        assertNull(t.verify(null));
        assertNull(t.verify("  "));
    }

    @Test
    void serviceVerificationRequiresDedicatedTokenUse() {
        InternalToken t = new InternalToken(SECRET, Duration.ofMinutes(5));
        TenantContext.Tenant tenant = new TenantContext.Tenant("acme", "evaluator", Set.of("eval"));

        assertNull(t.verifyService(t.mint(tenant)));
        assertEquals(tenant, t.verifyService(t.mintService(tenant)));
        // 下游收到 edge 换发后的同一服务令牌时，仍可按普通内部身份校验。
        assertEquals(tenant, t.verify(t.mintService(tenant)));
    }
}
