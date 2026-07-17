package com.lrj.platform.agent.client;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归：{@link OrderClient} 的 Http/Noop 互补门控。与 analytics 不同，order **默认关**——
 * 缺省时应由 {@link NoopOrderClient} 兜底（否则消费者 OrderQueryAction 注入失败、agent-service 起不来），
 * 仅 {@code app.agent.order.enabled=true} 时切到 {@link HttpOrderClient}。用 {@code @ConditionalOnExpression}
 * 精确二选一，避免 {@code @ConditionalOnMissingBean} 在组件扫描下的注册顺序不可靠。
 */
class OrderClientWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(NoopOrderClient.class, HttpOrderClient.class, StubRestTemplateConfig.class);

    @Test
    void orderDisabledByDefault_registersNoopFallback() {
        // agent 缺省 true、order 缺省 false → Noop 兜底，Http 不注册。
        runner.withPropertyValues("app.agent.enabled=true").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(HttpOrderClient.class);
            assertThat(ctx).hasSingleBean(OrderClient.class)
                    .getBean(OrderClient.class).isInstanceOf(NoopOrderClient.class);
        });
    }

    @Test
    void orderEnabled_useHttpNotNoop() {
        runner.withPropertyValues("app.agent.enabled=true", "app.agent.order.enabled=true").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(NoopOrderClient.class);
            assertThat(ctx).hasSingleBean(OrderClient.class)
                    .getBean(OrderClient.class).isInstanceOf(HttpOrderClient.class);
        });
    }

    @Test
    void agentDisabled_noOrderClientNeeded() {
        // agent 整体关闭时消费者 OrderQueryAction 也不注册，两实现都不注册是预期。
        runner.withPropertyValues("app.agent.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(OrderClient.class));
    }

    @Configuration
    static class StubRestTemplateConfig {
        @Bean
        RestTemplate orderRestTemplate() {
            return new RestTemplate();
        }
    }
}
