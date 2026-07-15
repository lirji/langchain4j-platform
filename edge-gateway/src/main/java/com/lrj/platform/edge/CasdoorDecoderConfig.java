package com.lrj.platform.edge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import java.util.ArrayList;
import java.util.List;

/**
 * enabled=true 时装配验 Casdoor RS256 token 的 {@link ReactiveJwtDecoder}（JWKS 自动缓存/轮转）+
 * timestamp/issuer/audience 校验。<strong>只用这一个组件</strong>在 {@code CasdoorTokenExchangeFilter}
 * 里手动验签，不启用 Spring Security 的 SecurityWebFilterChain（避免与现有 GlobalFilter 链冲突）。
 */
@Configuration
@EnableConfigurationProperties(CasdoorSecurityProperties.class)
@ConditionalOnProperty(prefix = "edge.casdoor", name = "enabled", havingValue = "true")
public class CasdoorDecoderConfig {

    @Bean
    public ReactiveJwtDecoder casdoorJwtDecoder(CasdoorSecurityProperties props) {
        // enabled 时 jwks/issuer/audiences 必须非空——iss/aud 校验是安全要求，不可因空配置被静默跳过。
        require(props.getJwkSetUri(), "edge.casdoor.jwk-set-uri");
        require(props.getIssuer(), "edge.casdoor.issuer");
        if (props.getAudiences() == null || props.getAudiences().isEmpty()) {
            throw new IllegalStateException("edge.casdoor.enabled=true 但 edge.casdoor.audiences 为空（必须校验 aud）");
        }
        // ONLY（严格 Casdoor-only）下 tenant 必须恒取已验签的 owner——防经配置漂移把 tenant 指向可伪造的 claim。
        if (props.getMode() == CasdoorSecurityProperties.Mode.ONLY && !"owner".equals(props.getTenantClaim())) {
            throw new IllegalStateException(
                    "edge.casdoor.mode=only 要求 tenant-claim=owner（已验签的 Casdoor org），当前=" + props.getTenantClaim());
        }
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(props.getJwkSetUri()).build();
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator());
        if (props.getIssuer() != null && !props.getIssuer().isBlank()) {
            validators.add(new JwtIssuerValidator(props.getIssuer()));
        }
        if (props.getAudiences() != null && !props.getAudiences().isEmpty()) {
            validators.add(audienceValidator(props.getAudiences()));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("edge.casdoor.enabled=true 但 " + name + " 为空");
        }
    }

    /**
     * aud 校验（支持 Casdoor Shared Application 的多租户 aud）：
     * <ul>
     *   <li>传统单 app：{@code aud} 恰等于某个允许的 client_id（membership，向后兼容）；</li>
     *   <li>Shared Application：{@code aud} 形如 {@code <base>-org-<org>}（<base>=允许的共享 app client_id）——
     *       此时额外绑定 {@code owner}（已验签的 Casdoor org=租户，不可伪造）必须等于 aud 里的 {@code <org>} 段，
     *       否则拒绝（防跨租户串权：拿 acme 的 base 却带 owner=beta 的 token 不放行）。</li>
     * </ul>
     * 实测（casdoor-multitenant-identity §C）：shared app 每 org 的 token aud=[{@code <base>-org-<org>}], owner=该 org。
     */
    static OAuth2TokenValidator<Jwt> audienceValidator(List<String> allowedBases) {
        return jwt -> {
            String owner = jwt.getClaimAsString("owner");   // 已验签的 Casdoor org
            String mismatch = null;
            for (String a : jwt.getAudience()) {
                for (String base : allowedBases) {
                    if (a.equals(base)) {
                        return OAuth2TokenValidatorResult.success();               // 传统单 app
                    }
                    String prefix = base + "-org-";
                    if (a.startsWith(prefix)) {
                        String org = a.substring(prefix.length());
                        if (!org.isEmpty() && org.equals(owner)) {
                            return OAuth2TokenValidatorResult.success();           // shared app：(owner,aud) 绑定成立
                        }
                        mismatch = "aud org '" + org + "' 与 owner '" + owner + "' 不符";
                    }
                }
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token", mismatch != null ? mismatch : "audience not allowed", null));
        };
    }
}
