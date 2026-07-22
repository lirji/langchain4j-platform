package com.lrj.platform.edge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Gateway 下游连接恢复配置。Docker 重建容器会保留服务名但更换 IP，因此 DNS 正/负缓存必须短于
 * 运维恢复窗口；连接池生命周期另由 application.yml 限制，避免继续复用已销毁容器的旧连接。
 */
@Configuration
public class EdgeHttpClientConfig {

    @Bean
    HttpClientCustomizer dockerDnsRefreshCustomizer(
            @Value("${edge.http-client.dns-cache-ttl:5s}") Duration dnsCacheTtl,
            @Value("${edge.http-client.dns-query-timeout:3s}") Duration queryTimeout) {
        return client -> client.resolver(spec -> spec
                .cacheMinTimeToLive(Duration.ZERO)
                .cacheMaxTimeToLive(dnsCacheTtl)
                .cacheNegativeTimeToLive(Duration.ZERO)
                .queryTimeout(queryTimeout));
    }
}
