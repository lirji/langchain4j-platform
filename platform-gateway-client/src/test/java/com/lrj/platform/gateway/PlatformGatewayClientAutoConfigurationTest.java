package com.lrj.platform.gateway;

import com.lrj.platform.gateway.tenant.EnvironmentTenantVirtualKeyResolver;
import com.lrj.platform.gateway.tenant.TenantAttributionMode;
import com.lrj.platform.gateway.tenant.TenantContextIdentityAutoConfiguration;
import com.lrj.platform.gateway.tenant.TenantIdentityProvider;
import com.lrj.platform.gateway.tenant.TenantVirtualKeyResolver;
import com.lrj.platform.security.TenantContext;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配测试：唯一 ChatModel Bean、三档绑定（非法值启动失败）、默认 Environment resolver 可被业务
 * Bean 覆盖、security 存在/缺失两种 classpath 下的身份来源行为。
 */
class PlatformGatewayClientAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PlatformGatewayClientAutoConfiguration.class,
                    TenantContextIdentityAutoConfiguration.class));

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void defaults_singleChatModelBean_noneAttribution_environmentResolver() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ChatModel.class)
                    .hasSingleBean(StreamingChatModel.class)
                    .hasSingleBean(GatewayChatModelFactory.class);
            assertThat(context.getBean(GatewayClientProperties.class).getTenantAttribution())
                    .isEqualTo(TenantAttributionMode.NONE);
            assertThat(context.getBean(TenantVirtualKeyResolver.class))
                    .isInstanceOf(EnvironmentTenantVirtualKeyResolver.class);
        });
    }

    @Test
    void tenantAttribution_bindsRelaxedValue_virtualKey() {
        runner.withPropertyValues("platform.gateway.tenant-attribution=virtual-key")
                .run(context -> assertThat(context.getBean(GatewayClientProperties.class).getTenantAttribution())
                        .isEqualTo(TenantAttributionMode.VIRTUAL_KEY));
    }

    @Test
    void tenantAttribution_illegalValue_failsStartup() {
        runner.withPropertyValues("platform.gateway.tenant-attribution=bogus")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void environmentResolver_readsPerTenantProperty_blankIsMissing() {
        runner.withPropertyValues("platform.gateway.tenant-virtual-keys.tenant-a=sk-vk-a",
                        "platform.gateway.tenant-virtual-keys.tenant-blank=   ")
                .run(context -> {
                    TenantVirtualKeyResolver resolver = context.getBean(TenantVirtualKeyResolver.class);
                    assertThat(resolver.resolve("tenant-a")).contains("sk-vk-a");
                    assertThat(resolver.resolve("tenant-blank")).isEmpty(); // 空白算缺失 → fail-closed
                    assertThat(resolver.resolve("tenant-none")).isEmpty();
                });
    }

    @Test
    void customResolverBean_overridesDefault() {
        runner.withUserConfiguration(CustomResolverConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(TenantVirtualKeyResolver.class);
                    assertThat(context.getBean(TenantVirtualKeyResolver.class).resolve("any"))
                            .contains("sk-custom");
                });
    }

    @Test
    void securityOnClasspath_identityComesFromTenantContext() {
        runner.run(context -> {
            TenantIdentityProvider identities = context.getBean(TenantIdentityProvider.class);
            assertThat(identities.currentTenantId()).isEqualTo("anonymous"); // 无上下文兜底
            TenantContext.set(new TenantContext.Tenant("tenant-42", "u1", Set.of()));
            assertThat(identities.currentTenantId()).isEqualTo("tenant-42");
        });
    }

    @Test
    void securityMissing_noIdentityBean_contextStillBoots() {
        runner.withClassLoader(new FilteredClassLoader(TenantContext.class))
                .withPropertyValues("platform.gateway.tenant-attribution=user")
                .run(context -> {
                    // TenantContextIdentityAutoConfiguration 经 @ConditionalOnClass 整体跳过
                    assertThat(context).doesNotHaveBean(TenantIdentityProvider.class);
                    // 工厂仍正常装配（匿名兜底 + 启动告警），eval-service 场景不炸
                    assertThat(context).hasSingleBean(ChatModel.class);
                });
    }

    @Test
    void virtualKeyMode_withTwoArgCompatConstructor_failsFast() {
        // 兼容构造器无动态 header 通道 —— virtual-key 档下若放行会静默回退 master key（业务规则禁止）
        GatewayClientProperties props = new GatewayClientProperties();
        props.setTenantAttribution(TenantAttributionMode.VIRTUAL_KEY);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new GatewayChatModelFactory(props, java.util.List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("virtual-key");
    }

    @Configuration
    static class CustomResolverConfig {
        @Bean
        TenantVirtualKeyResolver customResolver() {
            return tenantId -> Optional.of("sk-custom");
        }
    }
}
