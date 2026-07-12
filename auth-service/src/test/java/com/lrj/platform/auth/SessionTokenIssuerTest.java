package com.lrj.platform.auth;

import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SessionTokenIssuerTest {

    private final InternalSecurityProperties sec = new InternalSecurityProperties();
    private final SessionTokenIssuer issuer = new SessionTokenIssuer(sec);

    @Test
    void accessTokenIsVerifiableWithSessionSecret() {
        UserAccount user = new UserAccount("alice", "h", "acme", "alice", Set.of("chat", "ingest"), true);
        String jwt = issuer.mintAccessToken(user);

        // edge-gateway 会用同一会话密钥验签
        InternalToken verifier = new InternalToken(sec.getSession().getJwtSecret(), sec.getSession().getAccessTtl());
        TenantContext.Tenant t = verifier.verify(jwt);
        assertThat(t).isNotNull();
        assertThat(t.tenantId()).isEqualTo("acme");
        assertThat(t.userId()).isEqualTo("alice");
        assertThat(t.scopes()).containsExactlyInAnyOrder("chat", "ingest");
    }

    @Test
    void internalSecretCannotVerifySessionToken() {
        UserAccount user = new UserAccount("bob", "h", "globex", "bob", Set.of("chat"), true);
        String jwt = issuer.mintAccessToken(user);
        // 用内部令牌密钥（不同 secret）验签必须失败 —— 会话/内部令牌爆炸半径隔离
        InternalToken internal = new InternalToken(sec.getJwtSecret(), sec.getJwtTtl());
        assertThat(internal.verify(jwt)).isNull();
    }

    @Test
    void refreshTokenHashingIsDeterministicAndOpaque() {
        String raw = issuer.generateRefreshToken();
        assertThat(raw).isNotBlank();
        String h1 = issuer.hashRefreshToken(raw);
        String h2 = issuer.hashRefreshToken(raw);
        assertThat(h1).isEqualTo(h2).isNotEqualTo(raw);
        assertThat(issuer.hashRefreshToken(issuer.generateRefreshToken())).isNotEqualTo(h1);
    }
}
