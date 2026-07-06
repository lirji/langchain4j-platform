package com.lrj.platform.metering;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableConfigurationProperties(TokenBudgetProperties.class)
public class PlatformMeteringAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "app.token-budget.store", havingValue = "in-memory", matchIfMissing = true)
    public TokenBudgetTracker inMemoryTokenBudgetTracker(TokenBudgetProperties props) {
        return new InMemoryTokenBudgetTracker(props);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "app.token-budget.store", havingValue = "redis")
    public TokenBudgetTracker redisTokenBudgetTracker(StringRedisTemplate redis, TokenBudgetProperties props) {
        return new RedisTokenBudgetTracker(redis, props, props.getRedis().getKeyPrefix());
    }

    @Bean
    @ConditionalOnMissingBean(TokenBudgetChatModelListener.class)
    @ConditionalOnProperty(name = "app.token-budget.enabled", havingValue = "true", matchIfMissing = true)
    public ChatModelListener tokenBudgetChatModelListener(TokenBudgetTracker tracker) {
        return new TokenBudgetChatModelListener(tracker);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnAvailableEndpoint
    public TokenBudgetEndpoint tokenBudgetEndpoint(TokenBudgetTracker tracker) {
        return new TokenBudgetEndpoint(tracker);
    }
}
