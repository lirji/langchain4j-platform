package com.lrj.platform.metering;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static org.assertj.core.api.Assertions.assertThat;

/** 预检 filter 的装配条件：默认启用、可用 enforce=false 回滚、总开关关闭时不装。 */
class TokenBudgetGuardAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformMeteringAutoConfiguration.class))
            .withPropertyValues("app.token-budget.store=in-memory");

    @Test
    void guardIsRegisteredByDefaultAfterInternalAuth() {
        runner.run(ctx -> {
            assertThat(ctx).hasBean("tokenBudgetGuardFilter");
            FilterRegistrationBean<?> reg = ctx.getBean("tokenBudgetGuardFilter", FilterRegistrationBean.class);
            assertThat(reg.getOrder()).isEqualTo(Integer.MIN_VALUE + 30); // InternalTokenAuthFilter 为 +20
        });
    }

    @Test
    void enforceFalseKeepsAccountingOnly() {
        runner.withPropertyValues("app.token-budget.enforce=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean("tokenBudgetGuardFilter");
            assertThat(ctx).hasSingleBean(TokenBudgetTracker.class);
        });
    }

    @Test
    void budgetDisabledRegistersNoGuard() {
        runner.withPropertyValues("app.token-budget.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean("tokenBudgetGuardFilter"));
    }
}
