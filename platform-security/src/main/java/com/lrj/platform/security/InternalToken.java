package com.lrj.platform.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 内部租户传播令牌的签发 / 校验（HS256）。框架无关，servlet 与 reactive 两侧都能用。
 *
 * <p>边缘用 API key 换发一个短时 JWT（claims: tenantId=sub / userId / scopes），每个跨服务调用把它
 * 放进内部 header；下游校验签名 + 过期后重建 {@link TenantContext.Tenant}，无需再持有 API key 表。
 * 这是原单体所没有、微服务化后租户能跨网络跳的关键件。
 */
public final class InternalToken {

    private final SecretKey key;
    private final Duration ttl;

    public InternalToken(String secret, Duration ttl) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = ttl;
    }

    /** 把当前租户编成短时签名 JWT。 */
    public String mint(TenantContext.Tenant tenant) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(tenant.tenantId())
                .claim("uid", tenant.userId())
                .claim("scopes", List.copyOf(tenant.scopes() == null ? Set.of() : tenant.scopes()))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** 校验签名 + 过期，重建 Tenant；无效返回 null（调用方决定拒绝或降级 anonymous）。 */
    @SuppressWarnings("unchecked")
    public TenantContext.Tenant verify(String jwt) {
        if (jwt == null || jwt.isBlank()) return null;
        try {
            Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(jwt);
            Claims c = jws.getPayload();
            Object rawScopes = c.get("scopes");
            Set<String> scopes = new LinkedHashSet<>();
            if (rawScopes instanceof List<?> list) {
                for (Object o : list) scopes.add(String.valueOf(o));
            }
            String uid = c.get("uid", String.class);
            return new TenantContext.Tenant(c.getSubject(), uid, scopes);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
