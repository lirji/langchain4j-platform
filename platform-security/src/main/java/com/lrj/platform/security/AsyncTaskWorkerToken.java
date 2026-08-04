package com.lrj.platform.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 短时 async-task worker JWT。令牌绑定 service/tenant/actor/worker/task/action，且与普通内部
 * JWT 使用独立 HS256 key；普通用户令牌不能调用 worker 数据面。
 */
public final class AsyncTaskWorkerToken {

    private static final String TOKEN_USE = "async_task_worker";
    private static final String SCOPE = "async.task.worker";
    private static final Set<String> ACTIONS = Set.of("lease", "status", "event");
    private static final int MAX_TOKEN_LENGTH = 8_192;

    private final SecretKey key;
    private final Duration ttl;
    private final Duration clockSkew;
    private final String issuer;
    private final String audience;
    private final String keyId;
    private final String serviceId;

    public AsyncTaskWorkerToken(String secret,
                                Duration ttl,
                                Duration clockSkew,
                                String issuer,
                                String audience,
                                String keyId,
                                String serviceId) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("async worker JWT secret 必须至少 32 字节");
        }
        if (ttl == null || ttl.compareTo(Duration.ofSeconds(30)) < 0
                || ttl.compareTo(Duration.ofSeconds(120)) > 0) {
            throw new IllegalArgumentException("async worker JWT ttl 必须在 30 到 120 秒之间");
        }
        Duration safeSkew = clockSkew == null ? Duration.ZERO : clockSkew;
        if (safeSkew.isNegative() || safeSkew.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("async worker JWT clock-skew 必须在 0 到 30 秒之间");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = ttl;
        this.clockSkew = safeSkew;
        this.issuer = requireText(issuer, "issuer", 128);
        this.audience = requireText(audience, "audience", 128);
        this.keyId = requireText(keyId, "key-id", 128);
        this.serviceId = requireIdentity(serviceId, "service-id");
    }

    public String mint(TenantContext.Tenant tenant,
                       String workerId,
                       String action,
                       String taskId) {
        String safeWorker = requireIdentity(workerId, "worker-id");
        if (!belongsToService(safeWorker, serviceId)) {
            throw new IllegalArgumentException("async worker-id 必须属于当前 service-id");
        }
        if (!ACTIONS.contains(action)) {
            throw new IllegalArgumentException("不支持的 async worker action");
        }
        String safeTask = requireText(taskId, "task-id", 256);
        String tenantId = requireText(tenant.tenantId(), "tenant", 256);
        String actorUid = requireText(tenant.userId(), "actor-uid", 256);
        Instant now = Instant.now();
        return Jwts.builder()
                .header().type("JWT").keyId(keyId).and()
                .issuer(issuer)
                .audience().add(audience).and()
                .subject(serviceId)
                .claim("tenant", tenantId)
                .claim("actor_uid", actorUid)
                .claim("worker_id", safeWorker)
                .claim("scopes", List.of(SCOPE))
                .claim("token_use", TOKEN_USE)
                .claim("act", action)
                .claim("task_id", safeTask)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public String serviceId() {
        return serviceId;
    }

    public Principal verify(String token,
                            String expectedAction,
                            String expectedTaskId,
                            String expectedWorkerId) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH
                || !ACTIONS.contains(expectedAction)) {
            return null;
        }
        try {
            var jws = Jwts.parser()
                    .verifyWith(key)
                    .clockSkewSeconds(clockSkew.toSeconds())
                    .build()
                    .parseSignedClaims(token);
            Claims claims = jws.getPayload();
            String workerId = claims.get("worker_id", String.class);
            if (!"HS256".equals(jws.getHeader().getAlgorithm())
                    || !"JWT".equals(jws.getHeader().getType())
                    || !keyId.equals(jws.getHeader().getKeyId())
                    || !issuer.equals(claims.getIssuer())
                    || claims.getAudience() == null
                    || claims.getAudience().size() != 1
                    || !claims.getAudience().contains(audience)
                    || !TOKEN_USE.equals(claims.get("token_use", String.class))
                    || !expectedAction.equals(claims.get("act", String.class))
                    || !expectedTaskId.equals(claims.get("task_id", String.class))
                    || !validIdentity(workerId)
                    || !belongsToService(workerId, claims.getSubject())
                    || (expectedWorkerId != null && !expectedWorkerId.equals(workerId))
                    || !validJti(claims.getId())
                    || claims.getIssuedAt() == null
                    || claims.getExpiration() == null) {
                return null;
            }
            Instant issuedAt = claims.getIssuedAt().toInstant();
            Instant expiresAt = claims.getExpiration().toInstant();
            Duration lifetime = Duration.between(issuedAt, expiresAt);
            if (lifetime.isZero() || lifetime.isNegative()
                    || lifetime.compareTo(ttl.plus(clockSkew)) > 0
                    || issuedAt.isAfter(Instant.now().plus(clockSkew))) {
                return null;
            }
            Object scopes = claims.get("scopes");
            if (!(scopes instanceof List<?> list)
                    || list.size() != 1
                    || !SCOPE.equals(list.getFirst())) {
                return null;
            }
            String tenantId = claims.get("tenant", String.class);
            String actorUid = claims.get("actor_uid", String.class);
            if (!validText(tenantId, 256) || !validText(actorUid, 256)) {
                return null;
            }
            return new Principal(claims.getSubject(), tenantId, actorUid, workerId);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean validJti(String value) {
        if (!validText(value, 64)) return false;
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String requireIdentity(String value, String name) {
        String safe = requireText(value, name, 128);
        if (!validIdentity(safe)) {
            throw new IllegalArgumentException("async worker " + name + " 格式无效");
        }
        return safe;
    }

    private static String requireText(String value, String name, int maxLength) {
        if (!validText(value, maxLength)) {
            throw new IllegalArgumentException("async worker " + name + " 长度无效");
        }
        return value;
    }

    private static boolean validText(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength;
    }

    private static boolean validIdentity(String value) {
        return validText(value, 128) && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    }

    private static boolean belongsToService(String workerId, String serviceId) {
        return validIdentity(serviceId)
                && (serviceId.equals(workerId) || workerId.startsWith(serviceId + "."));
    }

    public record Principal(String serviceId, String tenantId, String actorUserId, String workerId) {
    }
}
