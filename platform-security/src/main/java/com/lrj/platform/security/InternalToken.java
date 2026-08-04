package com.lrj.platform.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParserBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 内部租户传播令牌的签发 / 校验。框架无关，servlet 与 reactive 两侧都能用。
 *
 * <p>支持两种签名算法：
 * <ul>
 *   <li><b>HS256</b>（默认）：共享对称密钥，签发/验签同一 secret。dev/test 零配置沿用此路径。</li>
 *   <li><b>RS256</b>（可选）：非对称。edge-gateway 持私钥签发，下游只持公钥验签，缩小轮转爆炸半径。</li>
 * </ul>
 *
 * <p>边缘用 API key 换发一个短时 JWT（严格绑定 issuer/audience/kid/token-use/jti/iat/exp 与
 * tenantId=sub / userId / scopes），每个跨服务调用把它放进内部 header；下游完整校验安全上下文后
 * 重建 {@link TenantContext.Tenant}，无需再持有 API key 表。
 * 这是原单体所没有、微服务化后租户能跨网络跳的关键件。
 */
public final class InternalToken {

    private static final String TOKEN_USE_CLAIM = "token_use";
    private static final String INTERNAL_TOKEN_USE = "internal_access";
    private static final String SERVICE_TOKEN_USE = "service_callback";
    private static final String DEFAULT_ISSUER = "langchain4j-platform";
    private static final String DEFAULT_AUDIENCE = "platform-internal";
    private static final String DEFAULT_KEY_ID = "platform-internal-v1";
    private static final Duration DEFAULT_CLOCK_SKEW = Duration.ofSeconds(5);

    /** 签发用密钥：HS256 为对称 {@link SecretKey}，RS256 为 {@link PrivateKey}；仅验签节点可为 null。 */
    private final Key signingKey;
    /** 验签用密钥：HS256 为对称 {@link SecretKey}，RS256 为 {@link PublicKey}；仅签发节点可为 null。 */
    private final Key verificationKey;
    private final Duration ttl;
    private final Duration clockSkew;
    private final String algorithm;
    private final String issuer;
    private final String audience;
    private final String keyId;

    /**
     * HS256（对称）构造：签发/验签共用同一 secret。向后兼容的既有入口。
     *
     * @param secret 共享密钥（≥32 字节，否则 jjwt 抛 WeakKeyException 快速失败）
     */
    public InternalToken(String secret, Duration ttl) {
        SecretKey k = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.signingKey = k;
        this.verificationKey = k;
        this.ttl = ttl;
        this.clockSkew = DEFAULT_CLOCK_SKEW;
        this.algorithm = "HS256";
        this.issuer = DEFAULT_ISSUER;
        this.audience = DEFAULT_AUDIENCE;
        this.keyId = DEFAULT_KEY_ID;
    }

    /** RS256（非对称）构造：{@code signingKey} 私钥可为 null（纯验签节点），{@code verificationKey} 公钥可为 null（纯签发节点）。 */
    private InternalToken(Key signingKey, Key verificationKey, Duration ttl,
                          Duration clockSkew, String algorithm, String issuer,
                          String audience, String keyId) {
        this.signingKey = signingKey;
        this.verificationKey = verificationKey;
        this.ttl = ttl;
        this.clockSkew = clockSkew;
        this.algorithm = algorithm;
        this.issuer = requireText(issuer, "issuer");
        this.audience = requireText(audience, "audience");
        this.keyId = requireText(keyId, "key-id");
    }

    /**
     * 按配置的算法构造。框架无关工厂，供自动装配调用。
     *
     * @param algorithm     {@code HS256}（默认，用 secret）或 {@code RS256}（用 keypair）；大小写不敏感
     * @param secret        HS256 共享密钥
     * @param privateKeyPem RS256 签发私钥（PKCS#8，PEM 或纯 base64；仅签发节点需要）
     * @param publicKeyPem  RS256 验签公钥（X.509，PEM 或纯 base64；仅验签节点需要）
     * @throws IllegalArgumentException 不支持的算法
     * @throws IllegalStateException    RS256 但私钥公钥均缺失，或 PEM 解析失败（快速失败）
     */
    public static InternalToken forAlgorithm(String algorithm, String secret,
                                             String privateKeyPem, String publicKeyPem, Duration ttl) {
        return forAlgorithm(algorithm, secret, privateKeyPem, publicKeyPem, ttl,
                DEFAULT_ISSUER, DEFAULT_AUDIENCE, DEFAULT_KEY_ID, DEFAULT_CLOCK_SKEW);
    }

    /** 按算法及完整令牌安全上下文构造，供自动装配与跨语言契约测试使用。 */
    public static InternalToken forAlgorithm(String algorithm, String secret,
                                             String privateKeyPem, String publicKeyPem, Duration ttl,
                                             String issuer, String audience, String keyId) {
        return forAlgorithm(algorithm, secret, privateKeyPem, publicKeyPem, ttl,
                issuer, audience, keyId, DEFAULT_CLOCK_SKEW);
    }

    /** 按算法、issuer/audience/kid 与时钟偏差构造。 */
    public static InternalToken forAlgorithm(String algorithm, String secret,
                                             String privateKeyPem, String publicKeyPem, Duration ttl,
                                             String issuer, String audience, String keyId,
                                             Duration clockSkew) {
        String alg = (algorithm == null || algorithm.isBlank())
                ? "HS256" : algorithm.trim().toUpperCase(Locale.ROOT);
        Duration safeClockSkew = clockSkew == null ? Duration.ZERO : clockSkew;
        if (safeClockSkew.isNegative() || safeClockSkew.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("内部 JWT clock-skew 必须在 0 到 30 秒之间");
        }
        switch (alg) {
            case "HS256":
                SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
                return new InternalToken(key, key, ttl, safeClockSkew, alg,
                        issuer, audience, keyId);
            case "RS256":
                PrivateKey priv = hasText(privateKeyPem) ? parsePrivateKey(privateKeyPem) : null;
                PublicKey pub = hasText(publicKeyPem) ? parsePublicKey(publicKeyPem) : null;
                if (priv == null && pub == null) {
                    throw new IllegalStateException(
                            "platform.security.jwt.algorithm=RS256 需要至少配置 private-key(签发) 或 public-key(验签)");
                }
                return new InternalToken(priv, pub, ttl, safeClockSkew, alg,
                        issuer, audience, keyId);
            default:
                throw new IllegalArgumentException(
                        "不支持的内部 JWT 算法: " + algorithm + "（仅支持 HS256 / RS256）");
        }
    }

    /** 把当前租户编成短时签名 JWT。 */
    public String mint(TenantContext.Tenant tenant) {
        return mint(tenant, null);
    }

    /** 签发仅供栈内服务回调 edge 的令牌；edge 会校验专用用途声明，普通内部 JWT 不能替代。 */
    public String mintService(TenantContext.Tenant tenant) {
        return mint(tenant, SERVICE_TOKEN_USE);
    }

    private String mint(TenantContext.Tenant tenant, String tokenUse) {
        if (signingKey == null) {
            throw new IllegalStateException(
                    "当前节点未配置签发密钥（RS256 需 platform.security.jwt.private-key），无法签发内部 JWT");
        }
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .header().type("JWT").keyId(keyId).and()
                .issuer(issuer)
                .audience().add(audience).and()
                .subject(tenant.tenantId())
                .claim("uid", tenant.userId())
                .claim("scopes", List.copyOf(tenant.scopes() == null ? Set.of() : tenant.scopes()))
                .claim(TOKEN_USE_CLAIM, tokenUse == null ? INTERNAL_TOKEN_USE : tokenUse)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)));
        // 可选加法字段 dept（部门层级授权用）：旧 reader 忽略此 claim；新 reader 遇旧 token 缺失时 department=null。
        if (tenant.department() != null && !tenant.department().isBlank()) {
            builder.claim("dept", tenant.department());
        }
        return switch (algorithm) {
            case "HS256" -> builder.signWith((SecretKey) signingKey, Jwts.SIG.HS256).compact();
            case "RS256" -> builder.signWith((PrivateKey) signingKey, Jwts.SIG.RS256).compact();
            default -> throw new IllegalStateException("未配置受支持的内部 JWT 签名算法: " + algorithm);
        };
    }

    /** 校验签名 + 过期，重建 Tenant；无效返回 null（调用方决定拒绝或降级 anonymous）。 */
    public TenantContext.Tenant verify(String jwt) {
        return verify(jwt, INTERNAL_TOKEN_USE);
    }

    /** 校验栈内服务回调令牌；缺少专用用途声明的普通内部 JWT 必须拒绝。 */
    public TenantContext.Tenant verifyService(String jwt) {
        return verify(jwt, SERVICE_TOKEN_USE);
    }

    private TenantContext.Tenant verify(String jwt, String expectedTokenUse) {
        if (jwt == null || jwt.isBlank()) return null;
        if (verificationKey == null) return null;
        try {
            JwtParserBuilder parser = Jwts.parser();
            if (verificationKey instanceof SecretKey sk) {
                parser.verifyWith(sk);
            } else if (verificationKey instanceof PublicKey pk) {
                parser.verifyWith(pk);
            } else {
                return null;
            }
            parser.clockSkewSeconds(clockSkew.toSeconds());
            Jws<Claims> jws = parser.build().parseSignedClaims(jwt);
            Claims c = jws.getPayload();
            if (!algorithm.equalsIgnoreCase(jws.getHeader().getAlgorithm())
                    || !"JWT".equals(jws.getHeader().getType())
                    || !keyId.equals(jws.getHeader().getKeyId())
                    || !issuer.equals(c.getIssuer())
                    || c.getAudience() == null
                    || c.getAudience().size() != 1
                    || !c.getAudience().contains(audience)
                    || !expectedTokenUse.equals(c.get(TOKEN_USE_CLAIM, String.class))
                    || !hasText(c.getId())
                    || c.getIssuedAt() == null
                    || c.getExpiration() == null) {
                return null;
            }
            Instant issuedAt = c.getIssuedAt().toInstant();
            Instant expiresAt = c.getExpiration().toInstant();
            Duration lifetime = Duration.between(issuedAt, expiresAt);
            if (lifetime.isNegative() || lifetime.isZero()
                    || lifetime.compareTo(ttl.plus(clockSkew)) > 0
                    || issuedAt.isAfter(Instant.now().plus(clockSkew))) {
                return null;
            }
            Object rawScopes = c.get("scopes");
            Set<String> scopes = new LinkedHashSet<>();
            if (rawScopes instanceof List<?> list) {
                if (list.size() > 64) return null;
                for (Object item : list) {
                    if (!(item instanceof String scope) || scope.isBlank() || scope.length() > 128
                            || !scopes.add(scope)) {
                        return null;
                    }
                }
            } else {
                return null;
            }
            String subject = c.getSubject();
            String uid = c.get("uid", String.class);
            String dept = c.get("dept", String.class);   // 旧 token 无 dept -> null（向后兼容）
            if (!hasText(subject) || !hasText(uid)
                    || subject.length() > 256 || uid.length() > 256
                    || (dept != null && (dept.isBlank() || dept.length() > 256))) {
                return null;
            }
            return new TenantContext.Tenant(subject, uid, scopes, dept);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String requireText(String value, String name) {
        if (!hasText(value) || value.length() > 128) {
            throw new IllegalArgumentException("内部 JWT " + name + " 必须为 1 到 128 字符");
        }
        return value;
    }

    private static PrivateKey parsePrivateKey(String pem) {
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePrivate(new PKCS8EncodedKeySpec(decodePem(pem)));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "无法解析内部 JWT RS256 私钥（应为 PKCS#8 PEM 或 base64）: " + e.getMessage(), e);
        }
    }

    private static PublicKey parsePublicKey(String pem) {
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePublic(new X509EncodedKeySpec(decodePem(pem)));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "无法解析内部 JWT RS256 公钥（应为 X.509 PEM 或 base64）: " + e.getMessage(), e);
        }
    }

    /** 去掉 PEM 头尾与空白，base64 解码为 DER 字节；纯 base64（无头尾）同样适用。 */
    private static byte[] decodePem(String raw) {
        String body = raw
                .replaceAll("-----BEGIN[^-]*-----", "")
                .replaceAll("-----END[^-]*-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
