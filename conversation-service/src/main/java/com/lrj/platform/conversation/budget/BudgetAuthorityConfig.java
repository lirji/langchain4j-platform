package com.lrj.platform.conversation.budget;

import com.lrj.platform.metering.budget.ReservationBudgetProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** API 只在 conversation 权威端启用，不给每个服务增加服务签名凭据。 */
@Configuration
@EnableConfigurationProperties(ReservationBudgetProperties.class)
@ConditionalOnProperty(name = "app.token-budget.reservations.api-enabled", havingValue = "true")
public class BudgetAuthorityConfig {
    @Bean
    FilterRegistrationBean<BudgetServiceAuthFilter> budgetServiceAuthFilter(ReservationBudgetProperties properties) {
        if (!properties.isEnabled()) throw new IllegalArgumentException("budget API requires reservation admission");
        var registration = new FilterRegistrationBean<>(new BudgetServiceAuthFilter(new BudgetServiceTokens(properties.getServiceSecret())));
        registration.addUrlPatterns("/internal/metering/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
