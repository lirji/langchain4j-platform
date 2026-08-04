package com.lrj.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.beans.factory.annotation.Value;
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
                props.getJwtTtl(),
                props.getJwt().getIssuer(),
                props.getJwt().getAudience(),
                props.getJwt().getKeyId(),
                props.getJwt().getClockSkew());
    }

    @Bean
    @ConditionalOnMissingBean
    public AsyncTaskWorkerToken asyncTaskWorkerToken(
            InternalSecurityProperties props,
            @Value("${spring.application.name:platform-service}") String applicationName) {
        InternalSecurityProperties.AsyncWorker worker = props.getAsyncWorker();
        String serviceId = worker.getServiceId() == null || worker.getServiceId().isBlank()
                ? applicationName : worker.getServiceId().trim();
        return new AsyncTaskWorkerToken(
                worker.getSecret(),
                worker.getTtl(),
                worker.getClockSkew(),
                worker.getIssuer(),
                worker.getAudience(),
                worker.getKeyId(),
                serviceId);
    }

    @Bean
    @ConditionalOnMissingBean
    public AsyncTaskWorkerTokenForwarder asyncTaskWorkerTokenForwarder(
            AsyncTaskWorkerToken tokens,
            InternalSecurityProperties props) {
        return new AsyncTaskWorkerTokenForwarder(
                tokens,
                props.getAsyncWorker().getHeader(),
                props.getInternalHeader());
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboundCallbackPolicy outboundCallbackPolicy(InternalSecurityProperties props) {
        return new OutboundCallbackPolicy(props.getCallback());
    }

    @Bean
    @ConditionalOnMissingBean
    public PublicPayloadRedactor publicPayloadRedactor(ObjectMapper objectMapper) {
        return new PublicPayloadRedactor(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboundTenantForwarder outboundTenantForwarder(InternalToken tokens,
                                                           InternalSecurityProperties props) {
        return new OutboundTenantForwarder(
                tokens,
                props.getInternalHeader(),
                props.getAsyncWorker().getHeader());
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboundServiceTokenForwarder outboundServiceTokenForwarder(
            InternalToken tokens, InternalSecurityProperties props) {
        return new OutboundServiceTokenForwarder(
                tokens, props.getServiceTokenHeader(), props.getServiceTokenAllowedOrigins());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "platform.security.http-resilience.enabled", havingValue = "true", matchIfMissing = true)
    public OutboundHttpResilienceInterceptor outboundHttpResilienceInterceptor(
            InternalSecurityProperties props) {
        return new OutboundHttpResilienceInterceptor(props.getHttpResilience());
    }

    @Bean
    @ConditionalOnProperty(name = "platform.security.http-resilience.enabled", havingValue = "true", matchIfMissing = true)
    public RestTemplateCustomizer outboundHttpResilienceCustomizer(
            OutboundHttpResilienceInterceptor interceptor) {
        return restTemplate -> {
            boolean present = restTemplate.getInterceptors().stream()
                    .anyMatch(OutboundHttpResilienceInterceptor.class::isInstance);
            if (!present) {
                restTemplate.getInterceptors().add(interceptor);
            }
        };
    }

    /** servlet 下游服务专属：入站 JWT 校验 filter。edge-gateway（reactive）不装配。 */
    @Configuration
    @ConditionalOnClass(name = "jakarta.servlet.Filter")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class ServletAuthConfig {

        @Bean
        @ConditionalOnProperty(name = "platform.security.http-resilience.enabled", havingValue = "true", matchIfMissing = true)
        public FilterRegistrationBean<RequestDeadlineFilter> requestDeadlineFilter(
                InternalSecurityProperties props) {
            RequestDeadlineFilter filter = new RequestDeadlineFilter(props.getHttpResilience());
            FilterRegistrationBean<RequestDeadlineFilter> reg = new FilterRegistrationBean<>(filter);
            reg.addUrlPatterns("/*");
            reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            return reg;
        }

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
