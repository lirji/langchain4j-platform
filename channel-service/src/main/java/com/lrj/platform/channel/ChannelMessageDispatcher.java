package com.lrj.platform.channel;

import com.lrj.platform.protocol.channel.ChannelMessageRequest;

public interface ChannelMessageDispatcher {

    ChannelDeliveryResult dispatch(String messageId, ChannelMessageRequest request);
}
