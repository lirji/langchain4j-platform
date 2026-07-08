package com.lrj.platform.agent.client;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归：agent 开启但 analytics 关闭时，必须仍有一个 {@link AnalyticsClient} 兜底 bean
 * （{@link NoopAnalyticsClient}），否则唯一消费者 AnalyticsSqlAction 构造注入失败、agent-service 起不来。
 *
 * <p>历史缺陷：Noop 曾用 {@code @ConditionalOnMissingBean}，在组件扫描下注册顺序不可靠，
 * analytics 关闭(Http 未注册)时会随机丢失兜底 bean。现改为与 Http 互补的 {@code @ConditionalOnExpression}。
 */
class AnalyticsClientWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(NoopAnalyticsClient.class, HttpAnalyticsClient.class, StubRestTemplateConfig.class);

    @Test
    void analyticsDisabled_registersNoopFallback() {
        runner.withPropertyValues("app.agent.enabled=true", "app.agent.analytics.enabled=false")
                .run(ctx -> assertThat(ctx).hasSingleBean(AnalyticsClient.class)
                        .getBean(AnalyticsClient.class).isInstanceOf(NoopAnalyticsClient.class));
    }

    @Test
    void defaults_useHttpNotNoop() {
        // 两属性缺省 = true → Http 接管，Noop 让位。
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(NoopAnalyticsClient.class);
            assertThat(ctx).hasSingleBean(AnalyticsClient.class)
                    .getBean(AnalyticsClient.class).isInstanceOf(HttpAnalyticsClient.class);
        });
    }

    @Test
    void agentDisabled_noAnalyticsClientNeeded() {
        // agent 整体关闭时消费者 AnalyticsSqlAction 也不注册，两实现都不注册是预期。
        runner.withPropertyValues("app.agent.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(AnalyticsClient.class));
    }

    @Configuration
    static class StubRestTemplateConfig {
        @Bean
        RestTemplate analyticsRestTemplate() {
            return new RestTemplate();
        }
    }
}
