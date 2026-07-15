package com.lrj.platform.edge;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CasdoorDecoderConfig#audienceValidator} 的多租户 aud 校验单测（方案 C：Casdoor Shared Application）。
 * 关键安全不变量：shared app 的 aud=&lt;base&gt;-org-&lt;org&gt; 必须与已验签的 owner 绑定，防跨租户串权。
 */
class CasdoorAudienceValidatorTest {

    private static Jwt jwt(String owner, String... auds) {
        return Jwt.withTokenValue("t").header("alg", "none")
                .claim("owner", owner)
                .claim("aud", List.of(auds))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    private static boolean ok(Jwt jwt) {
        return !CasdoorDecoderConfig.audienceValidator(List.of("base")).validate(jwt).hasErrors();
    }

    @Test
    void exactBase_backwardCompat_singleApp() {
        assertThat(ok(jwt("acme", "base"))).isTrue();   // 传统单 app：aud==允许的 client_id
    }

    @Test
    void sharedApp_ownerMatchesAudOrg() {
        assertThat(ok(jwt("acme", "base-org-acme"))).isTrue();
        assertThat(ok(jwt("beta", "base-org-beta"))).isTrue();
    }

    @Test
    void sharedApp_ownerMismatch_rejected() {
        // aud 声称 acme 的 app，但 owner=beta（伪造/错配）→ 拒绝
        assertThat(ok(jwt("beta", "base-org-acme"))).isFalse();
    }

    @Test
    void unrelatedAud_rejected() {
        assertThat(ok(jwt("acme", "someone-else-client"))).isFalse();
    }
}
