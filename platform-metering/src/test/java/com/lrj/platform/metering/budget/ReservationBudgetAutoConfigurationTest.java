package com.lrj.platform.metering.budget;

import com.lrj.platform.metering.PlatformMeteringAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class ReservationBudgetAutoConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PlatformMeteringAutoConfiguration.class, ReservationBudgetAutoConfiguration.class)
            .withPropertyValues("app.token-budget.store=in-memory");
    @Test void disabledModePreservesOriginalBeans() {
        context.run(c -> assertThat(c).doesNotHaveBean(BudgetLedger.class));
    }
    @Test void enabledModeRequiresSharedTimezoneAndCreatesDecorator() {
        context.withPropertyValues("app.token-budget.reservations.enabled=true").run(c -> assertThat(c).hasFailed());
        context.withPropertyValues("app.token-budget.reservations.enabled=true", "app.token-budget.timezone=UTC")
                .run(c -> {
                    assertThat(c).hasSingleBean(BudgetLedger.class);
                    assertThat(c).hasSingleBean(com.lrj.platform.gateway.ChatModelDecorator.class);
                });
    }
}
