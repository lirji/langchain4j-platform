package com.lrj.platform.gateway.tenant;

import java.util.Optional;

/**
 * tenant → LiteLLM virtual key 的解析 SPI（仅 {@link TenantAttributionMode#VIRTUAL_KEY} 用到）。
 *
 * <p>默认实现 {@link EnvironmentTenantVirtualKeyResolver} 从 Spring Environment 读
 * {@code platform.gateway.tenant-virtual-keys.<tenantId>}（Secret / 环境变量注入，不落配置文件）；
 * 生产可换 Vault / Redis 实现 —— 自定义 Bean 即覆盖默认（{@code @ConditionalOnMissingBean}）。
 *
 * <p>契约：返回 {@link Optional#empty()} 表示「无 key」，调用侧 fail-closed；实现不得把 key 写日志。
 */
@FunctionalInterface
public interface TenantVirtualKeyResolver {

    /** 解析该租户的 virtual key；缺失/空白返回 empty（绝不回退 master key）。 */
    Optional<String> resolve(String tenantId);
}
