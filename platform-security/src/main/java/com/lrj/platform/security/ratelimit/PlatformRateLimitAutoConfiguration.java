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

    // 默认持久化到 Redis（跨重启/多 pod 共享计数）；设 app.rate-limit.store=in-memory 回退单 JVM。
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "app.rate-limit.store", havingValue = "in-memory")
    public RateLimiterRegistry inMemoryRateLimiterRegistry(RateLimitProperties props) {
        return new InMemoryRateLimiterRegistry(props);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "app.rate-limit.store", havingValue = "redis", matchIfMissing = true)
    public RateLimiterRegistry redisRateLimiterRegistry(StringRedisTemplate redis, RateLimitProperties props) {
        return new RedisRateLimiterRegistry(redis, props, props.getRedis().getKeyPrefix());
    }
}
