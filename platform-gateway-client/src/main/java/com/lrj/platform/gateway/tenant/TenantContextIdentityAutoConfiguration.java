package com.lrj.platform.gateway.tenant;

import com.lrj.platform.security.TenantContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * security 在 classpath 时自动提供 {@link TenantIdentityProvider}：取
 * {@code TenantContext.current().tenantId()}（无上下文时 TenantContext 自身兜底 anonymous）。
 *
 * <p>gateway-client 对 platform-security 是 <strong>optional</strong> 依赖 —— eval-service 等
 * 只依赖 gateway-client 的服务 classpath 上没有 {@code TenantContext}，本配置整体跳过
 * （{@code @ConditionalOnClass}），不会传递引入安全 filter。业务自定义 Bean 覆盖本默认
 * （{@code @ConditionalOnMissingBean}）。
 */
@Configuration
@ConditionalOnClass(TenantContext.class)
public class TenantContextIdentityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TenantIdentityProvider.class)
    public TenantIdentityProvider tenantContextIdentityProvider() {
        return () -> TenantContext.current().tenantId();
    }
}
