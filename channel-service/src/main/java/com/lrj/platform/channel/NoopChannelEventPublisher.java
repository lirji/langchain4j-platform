package com.lrj.platform.channel;

import com.lrj.platform.protocol.channel.ChannelEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// 与 KafkaChannelEventPublisher 用同一属性互斥（events 关闭/缺省时兜底）。
// 不用 @ConditionalOnMissingBean：在组件扫描下它对 @Component 的注册顺序敏感、不可靠
// （Spring 官方仅推荐用于自动配置类），曾导致 events 关闭时 ChannelEventPublisher bean 缺失、服务启动随机失败。
@Component
@ConditionalOnProperty(prefix = "app.channel", name = "events-enabled", havingValue = "false", matchIfMissing = true)
public class NoopChannelEventPublisher implements ChannelEventPublisher {

    @Override
    public void publish(ChannelEvent event) {
        // Event publishing is optional and disabled by default.
    }
}
