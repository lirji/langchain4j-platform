package com.lrj.platform.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * platform-security 自动装配：内部 JWT 工具 + 出站传播拦截器（框架无关，两侧都建）；
 * 下游服务的入站 JWT 校验 filter 仅在 servlet Web 应用装配（reactive 的 edge-gateway 自己处理鉴权）。
 */
@Configuration
@EnableConfigurationProperties(InternalSecurityProperties.class)
public class PlatformSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public InternalToken internalToken(InternalSecurityProperties props) {
        return InternalToken.forAlgorithm(
                props.getJwt().getAlgorithm(),
                props.getJwtSecret(),
                props.getJwt().getPrivateKey(),
                props.getJwt().getPublicKey(),
                props.getJwtTtl());
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboundTenantForwarder outboundTenantForwarder(InternalToken tokens,
                                                           InternalSecurityProperties props) {
        return new OutboundTenantForwarder(tokens, props.getInternalHeader());
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboundServiceTokenForwarder outboundServiceTokenForwarder(
            InternalToken tokens, InternalSecurityProperties props) {
        return new OutboundServiceTokenForwarder(
                tokens, props.getServiceTokenHeader(), props.getServiceTokenAllowedOrigins());
    }

    /** servlet 下游服务专属：入站 JWT 校验 filter。edge-gateway（reactive）不装配。 */
    @Configuration
    @ConditionalOnClass(name = "jakarta.servlet.Filter")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class ServletAuthConfig {

        @Bean
        public FilterRegistrationBean<InternalTokenAuthFilter> internalTokenAuthFilter(
                InternalToken tokens, InternalSecurityProperties props) {
            InternalTokenAuthFilter filter =
                    new InternalTokenAuthFilter(tokens, props, props.isAllowApiKeyFallback());
            FilterRegistrationBean<InternalTokenAuthFilter> reg = new FilterRegistrationBean<>(filter);
            reg.addUrlPatterns("/*");
            reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
            return reg;
        }
    }
}
