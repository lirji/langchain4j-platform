package com.lrj.platform.metering;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * platform-metering 计量子系统的自动装配：按 {@code app.token-budget.store} 选择 {@link TokenBudgetTracker}
 * 实现（默认 {@link RedisTokenBudgetTracker} 跨 pod 共享预算，{@code in-memory} 回退单 JVM），并挂载
 * 按租户扣减 token 预算的 {@link TokenBudgetChatModelListener}（{@link ChatModelListener} SPI）与查询用的
 * {@link TokenBudgetEndpoint}；servlet 服务另挂入口预检 {@link TokenBudgetGuardFilter}（用满即 429）。
 */
@Configuration
@EnableConfigurationProperties(TokenBudgetProperties.class)
public class PlatformMeteringAutoConfiguration {

    // 默认持久化到 Redis（跨重启/多 pod 共享预算计数）；设 app.token-budget.store=in-memory 回退单 JVM。
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "app.token-budget.store", havingValue = "in-memory")
    public TokenBudgetTracker inMemoryTokenBudgetTracker(TokenBudgetProperties props) {
        return new InMemoryTokenBudgetTracker(props);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "app.token-budget.store", havingValue = "redis", matchIfMissing = true)
    public TokenBudgetTracker redisTokenBudgetTracker(StringRedisTemplate redis, TokenBudgetProperties props) {
        return new RedisTokenBudgetTracker(redis, props, props.getRedis().getKeyPrefix());
    }

    @Bean
    @ConditionalOnMissingBean(TokenBudgetChatModelListener.class)
    @ConditionalOnProperty(name = "app.token-budget.enabled", havingValue = "true", matchIfMissing = true)
    public ChatModelListener tokenBudgetChatModelListener(TokenBudgetTracker tracker) {
        return new TokenBudgetChatModelListener(tracker);
    }

    /**
     * 入口预检。order 在 {@code InternalTokenAuthFilter}（HIGHEST_PRECEDENCE + 20）之后，保证租户已绑定。
     * {@code app.token-budget.enforce=false}（{@code TOKEN_BUDGET_ENFORCE}）回滚为只记账。
     */
    @Configuration
    @ConditionalOnClass(name = "jakarta.servlet.Filter")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(name = {"app.token-budget.enabled", "app.token-budget.enforce"},
            havingValue = "true", matchIfMissing = true)
    static class TokenBudgetGuardConfig {

        @Bean
        public FilterRegistrationBean<TokenBudgetGuardFilter> tokenBudgetGuardFilter(
                TokenBudgetTracker tracker, TokenBudgetProperties props, ObjectProvider<MeterRegistry> meters) {
            TokenBudgetGuardFilter filter = new TokenBudgetGuardFilter(
                    tracker, props.getGuard().getPaths(), meters.getIfAvailable());
            FilterRegistrationBean<TokenBudgetGuardFilter> reg = new FilterRegistrationBean<>(filter);
            reg.addUrlPatterns("/*");
            reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
            return reg;
        }
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnAvailableEndpoint
    public TokenBudgetEndpoint tokenBudgetEndpoint(TokenBudgetTracker tracker) {
        return new TokenBudgetEndpoint(tracker);
    }
}
