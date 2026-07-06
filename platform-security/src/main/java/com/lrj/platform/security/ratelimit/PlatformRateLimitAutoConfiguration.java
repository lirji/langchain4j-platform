package com.lrj.platform.security.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class PlatformRateLimitAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "app.rate-limit.store", havingValue = "in-memory", matchIfMissing = true)
    public RateLimiterRegistry inMemoryRateLimiterRegistry(RateLimitProperties props) {
        return new InMemoryRateLimiterRegistry(props);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "app.rate-limit.store", havingValue = "redis")
    public RateLimiterRegistry redisRateLimiterRegistry(StringRedisTemplate redis, RateLimitProperties props) {
        return new RedisRateLimiterRegistry(redis, props, props.getRedis().getKeyPrefix());
    }
}
