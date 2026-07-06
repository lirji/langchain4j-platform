package com.lrj.platform.channel;

import com.lrj.platform.protocol.channel.ChannelEvent;

public interface ChannelEventPublisher {

    void publish(ChannelEvent event);
}
