package com.lrj.platform.channel;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归：events 关闭/缺省时必须仍有一个 {@link ChannelEventPublisher} 兜底 bean，否则
 * {@link ChannelCallbackService} 构造注入失败、整个 channel-service 随机起不来。
 *
 * <p>历史缺陷：{@link NoopChannelEventPublisher} 曾用 {@code @ConditionalOnMissingBean}，
 * 在组件扫描下注册顺序不可靠，导致默认(events off)配置下偶发无 ChannelEventPublisher bean。
 */
class ChannelEventPublisherWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(NoopChannelEventPublisher.class, KafkaChannelEventPublisher.class);

    @Test
    void eventsDisabled_registersNoopFallback() {
        runner.withPropertyValues("app.channel.events-enabled=false")
                .run(ctx -> assertThat(ctx).hasSingleBean(ChannelEventPublisher.class)
                        .getBean(ChannelEventPublisher.class).isInstanceOf(NoopChannelEventPublisher.class));
    }

    @Test
    void eventsPropertyMissing_registersNoopFallback() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(ChannelEventPublisher.class)
                .getBean(ChannelEventPublisher.class).isInstanceOf(NoopChannelEventPublisher.class));
    }
}
