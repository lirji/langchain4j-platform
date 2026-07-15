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

/**
 * 内部租户传播令牌的签发 / 校验。框架无关，servlet 与 reactive 两侧都能用。
 *
 * <p>支持两种签名算法：
 * <ul>
 *   <li><b>HS256</b>（默认）：共享对称密钥，签发/验签同一 secret。dev/test 零配置沿用此路径。</li>
 *   <li><b>RS256</b>（可选）：非对称。edge-gateway 持私钥签发，下游只持公钥验签，缩小轮转爆炸半径。</li>
 * </ul>
 *
 * <p>边缘用 API key 换发一个短时 JWT（claims: tenantId=sub / userId / scopes），每个跨服务调用把它
 * 放进内部 header；下游校验签名 + 过期后重建 {@link TenantContext.Tenant}，无需再持有 API key 表。
 * 这是原单体所没有、微服务化后租户能跨网络跳的关键件。
 */
public final class InternalToken {

    /** 签发用密钥：HS256 为对称 {@link SecretKey}，RS256 为 {@link PrivateKey}；仅验签节点可为 null。 */
    private final Key signingKey;
    /** 验签用密钥：HS256 为对称 {@link SecretKey}，RS256 为 {@link PublicKey}；仅签发节点可为 null。 */
    private final Key verificationKey;
    private final Duration ttl;

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
    }

    /** RS256（非对称）构造：{@code signingKey} 私钥可为 null（纯验签节点），{@code verificationKey} 公钥可为 null（纯签发节点）。 */
    private InternalToken(PrivateKey signingKey, PublicKey verificationKey, Duration ttl) {
        this.signingKey = signingKey;
        this.verificationKey = verificationKey;
        this.ttl = ttl;
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
        String alg = (algorithm == null || algorithm.isBlank())
                ? "HS256" : algorithm.trim().toUpperCase(Locale.ROOT);
        switch (alg) {
            case "HS256":
                return new InternalToken(secret, ttl);
            case "RS256":
                PrivateKey priv = hasText(privateKeyPem) ? parsePrivateKey(privateKeyPem) : null;
                PublicKey pub = hasText(publicKeyPem) ? parsePublicKey(publicKeyPem) : null;
                if (priv == null && pub == null) {
                    throw new IllegalStateException(
                            "platform.security.jwt.algorithm=RS256 需要至少配置 private-key(签发) 或 public-key(验签)");
                }
                return new InternalToken(priv, pub, ttl);
            default:
                throw new IllegalArgumentException(
                        "不支持的内部 JWT 算法: " + algorithm + "（仅支持 HS256 / RS256）");
        }
    }

    /** 把当前租户编成短时签名 JWT。 */
    public String mint(TenantContext.Tenant tenant) {
        if (signingKey == null) {
            throw new IllegalStateException(
                    "当前节点未配置签发密钥（RS256 需 platform.security.jwt.private-key），无法签发内部 JWT");
        }
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(tenant.tenantId())
                .claim("uid", tenant.userId())
                .claim("scopes", List.copyOf(tenant.scopes() == null ? Set.of() : tenant.scopes()))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)));
        // 可选加法字段 dept（部门层级授权用）：旧 reader 忽略此 claim；新 reader 遇旧 token 缺失时 department=null。
        if (tenant.department() != null && !tenant.department().isBlank()) {
            builder.claim("dept", tenant.department());
        }
        return builder.signWith(signingKey).compact();
    }

    /** 校验签名 + 过期，重建 Tenant；无效返回 null（调用方决定拒绝或降级 anonymous）。 */
    public TenantContext.Tenant verify(String jwt) {
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
            Jws<Claims> jws = parser.build().parseSignedClaims(jwt);
            Claims c = jws.getPayload();
            Object rawScopes = c.get("scopes");
            Set<String> scopes = new LinkedHashSet<>();
            if (rawScopes instanceof List<?> list) {
                for (Object o : list) scopes.add(String.valueOf(o));
            }
            String uid = c.get("uid", String.class);
            String dept = c.get("dept", String.class);   // 旧 token 无 dept -> null（向后兼容）
            return new TenantContext.Tenant(c.getSubject(), uid, scopes, dept);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
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
