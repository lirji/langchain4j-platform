package com.lrj.platform.gateway.tenant;

import org.springframework.core.env.Environment;

import java.util.Optional;

/**
 * 默认 {@link TenantVirtualKeyResolver}：从 Spring {@link Environment} 读
 * {@code platform.gateway.tenant-virtual-keys.<tenantId>}。
 *
 * <p>刻意<strong>不</strong>用 {@code @ConfigurationProperties} Map 承载 —— 避免 key 集中出现在
 * {@code /actuator/configprops} / env dump 的单一 bean 里扩大暴露面；Environment 逐 key 查询，
 * 值经环境变量（relaxed binding：{@code PLATFORM_GATEWAY_TENANT_VIRTUAL_KEYS_<TENANT>}）或
 * Secret 注入，不进 git。
 *
 * <p>trim 后空白视为缺失（返回 empty → 调用侧 fail-closed）。
 */
public class EnvironmentTenantVirtualKeyResolver implements TenantVirtualKeyResolver {

    /** 属性前缀；完整 key 为 {@code platform.gateway.tenant-virtual-keys.<tenantId>}。 */
    public static final String PROPERTY_PREFIX = "platform.gateway.tenant-virtual-keys.";

    private final Environment environment;

    public EnvironmentTenantVirtualKeyResolver(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Optional<String> resolve(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        String value = environment.getProperty(PROPERTY_PREFIX + tenantId);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.trim());
    }
}
