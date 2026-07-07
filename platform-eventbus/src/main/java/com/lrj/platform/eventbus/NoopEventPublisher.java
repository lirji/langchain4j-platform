package com.lrj.platform.eventbus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认事件发布实现：不投递任何外部系统，仅 debug 记录。
 * 保证 {@code platform.eventbus.enabled=false}（默认）时全链零 Kafka 依赖。
 */
public class NoopEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(NoopEventPublisher.class);

    @Override
    public void publish(String topic, String key, Object payload) {
        log.debug("[eventbus-noop] drop event topic={} key={} payload={}", topic, key, payload);
    }
}
