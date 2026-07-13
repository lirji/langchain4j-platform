package com.lrj.platform.auth;

import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.TenantContext;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

/**
 * 会话令牌签发：
 * <ul>
 *   <li>会话访问 JWT —— 复用 {@link InternalToken}（会话密钥 + access-ttl），claim 形状与内部 JWT 一致，
 *       edge-gateway 用同一密钥验签后换发内部 JWT。</li>
 *   <li>刷新令牌 —— 256bit 随机不透明串（base64url），服务端只存其 SHA-256 哈希。</li>
 * </ul>
 */
@Component
public class SessionTokenIssuer {

    private final InternalToken accessTokens;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final SecureRandom random = new SecureRandom();

    public SessionTokenIssuer(InternalSecurityProperties props) {
        InternalSecurityProperties.Session s = props.getSession();
        this.accessTtl = s.getAccessTtl();
        this.refreshTtl = s.getRefreshTtl();
        // 专用会话令牌实例，与平台默认的内部 JWT bean（内部密钥、5min）刻意分离。
        this.accessTokens = new InternalToken(s.getJwtSecret(), s.getAccessTtl());
    }

    /** 用账号身份签发会话访问 JWT（sub=tenant / uid=userId / scopes=账号直配 scopes）。 */
    public String mintAccessToken(UserAccount user) {
        return mintAccessToken(user, user.scopes());
    }

    /**
     * 用账号身份 + 指定的**有效 scopes** 签发会话访问 JWT。RBAC 下由 {@code AuthService} 传入
     * {@code RoleService.effectiveScopes(user)}（角色展开 ∪ 直配），签进 JWT 的 scopes claim。
     */
    public String mintAccessToken(UserAccount user, Set<String> effectiveScopes) {
        return accessTokens.mint(new TenantContext.Tenant(user.tenant(), user.userId(), effectiveScopes));
    }

    /** 生成新的刷新令牌原串（放进 cookie，服务端不留明文）。 */
    public String generateRefreshToken() {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /** 刷新令牌的存储/查找哈希（SHA-256 十六进制）。 */
    public String hashRefreshToken(String rawToken) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public long accessTtlSeconds() {
        return accessTtl.toSeconds();
    }

    public Duration refreshTtl() {
        return refreshTtl;
    }
}
