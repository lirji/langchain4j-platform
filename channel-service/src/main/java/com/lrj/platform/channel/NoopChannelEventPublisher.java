package com.lrj.platform.channel;

import com.lrj.platform.protocol.channel.ChannelEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(ChannelEventPublisher.class)
public class NoopChannelEventPublisher implements ChannelEventPublisher {

    @Override
    public void publish(ChannelEvent event) {
        // Event publishing is optional and disabled by default.
    }
}
