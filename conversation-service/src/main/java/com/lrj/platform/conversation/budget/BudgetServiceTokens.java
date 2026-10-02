package com.lrj.platform.conversation.budget;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.SecretKey;

/** 独立预算服务凭据，不能签发普通用户令牌；短 TTL 与 RPC 参数绑定缩小重放面。 */
final class BudgetServiceTokens {
    private final SecretKey key;
    BudgetServiceTokens(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalArgumentException("budget service signing key must contain at least 32 bytes");
        key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    Claims verify(String bearer, String action) {
        if (bearer == null || !bearer.startsWith("Bearer ")) return null;
        try {
            var signed = Jwts.parser().verifyWith(key).requireIssuer("agentscope-platform")
                    .requireAudience("platform-metering").requireSubject("agentscope-platform").require("token_use", "tenant_budget")
                    .require("act", action).clockSkewSeconds(5).build().parseSignedClaims(bearer.substring(7));
            var c = signed.getPayload();
            if (!"HS256".equals(signed.getHeader().getAlgorithm())
                    || !"metering-v1".equals(signed.getHeader().getKeyId())
                    || !"JWT".equals(signed.getHeader().getType())
                    || c.getIssuedAt() == null || c.getExpiration() == null || c.getId() == null || c.getId().isBlank()
                    || c.getExpiration().getTime() - c.getIssuedAt().getTime() > 30000
                    || c.getIssuedAt().toInstant().isAfter(Instant.now().plusSeconds(5))
                    || !c.getExpiration().after(c.getIssuedAt())) return null;
            for (String field : new String[]{"tenant", "actor_uid", "operation_id"}) {
                String value = c.get(field, String.class);
                if (value == null || value.isBlank() || value.length() > ("operation_id".equals(field) ? 128 : 256) || value.indexOf('\0') >= 0) return null;
            }
            return c;
        } catch (RuntimeException invalid) { return null; }
    }
}
