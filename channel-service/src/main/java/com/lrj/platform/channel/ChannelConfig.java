package com.lrj.platform.channel;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * channel-service 的 Spring 配置：启用 {@link ChannelProperties} 绑定，并提供带连接/读取超时
 * （取自 {@code app.channel} 配置）的 {@code channelRestTemplate}，供出站投递（webhook/飞书/语音）复用。
 */
@Configuration
@EnableConfigurationProperties(ChannelProperties.class)
public class ChannelConfig {

    @Bean
    RestTemplate channelRestTemplate(RestTemplateBuilder builder, ChannelProperties properties) {
        return builder
                .setConnectTimeout(properties.getConnectTimeout())
                .setReadTimeout(properties.getReadTimeout())
                .build();
    }
}
