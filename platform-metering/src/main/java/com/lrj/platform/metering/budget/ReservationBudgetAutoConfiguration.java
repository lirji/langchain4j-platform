package com.lrj.platform.metering.budget;

import com.lrj.platform.metering.TokenBudgetProperties;
import com.lrj.platform.metering.TokenBudgetTracker;
import com.lrj.platform.gateway.ChatModelDecorator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Clock;
import java.time.ZoneId;

/** 按独立开关渐进启用，默认不改变既有调用行为；开启后模型准入 fail-closed。 */
@Configuration
@EnableConfigurationProperties({ReservationBudgetProperties.class, TokenBudgetProperties.class})
@ConditionalOnProperty(name = "app.token-budget.reservations.enabled", havingValue = "true")
public class ReservationBudgetAutoConfiguration {
    @Bean
    @ConditionalOnProperty(name = "app.token-budget.store", havingValue = "redis", matchIfMissing = true)
    BudgetLedger redisBudgetLedger(StringRedisTemplate redis, TokenBudgetProperties props) {
        return new RedisBudgetLedger(redis, props, clock(props));
    }

    @Bean
    @ConditionalOnProperty(name = "app.token-budget.store", havingValue = "in-memory")
    BudgetLedger inMemoryBudgetLedger(TokenBudgetTracker tracker, TokenBudgetProperties props) {
        return new InMemoryBudgetLedger(tracker, props, clock(props));
    }

    @Bean
    ChatModelDecorator budgetModelDecorator(BudgetLedger ledger, ReservationBudgetProperties props) {
        return new BudgetModelDecorator(ledger, props);
    }

    /** MVC 异常转换只挂 servlet 栈，不影响非 Web 模型消费者。 */
    @Configuration
    @org.springframework.boot.autoconfigure.condition.ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestControllerAdvice")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
    static class WebErrors {
        @Bean BudgetErrorAdvice budgetErrorAdvice(TokenBudgetTracker tracker) { return new BudgetErrorAdvice(tracker); }
    }

    private Clock clock(TokenBudgetProperties props) {
        if (props.getTimezone() == null || props.getTimezone().isBlank())
            throw new IllegalArgumentException("shared reservation budget requires an explicit day timezone");
        return Clock.system(ZoneId.of(props.getTimezone()));
    }
}
