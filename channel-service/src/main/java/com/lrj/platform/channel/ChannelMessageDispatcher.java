package com.lrj.platform.channel;

import com.lrj.platform.protocol.channel.ChannelMessageRequest;

/**
 * 出站消息投递抽象：把一条 {@link ChannelMessageRequest} 发往目标渠道（webhook/飞书/语音等），
 * 返回 {@link ChannelDeliveryResult}。遵循「接口 + 可插拔实现」约定，
 * {@link HttpChannelMessageDispatcher} 是基于 HTTP 的默认实现。
 */
public interface ChannelMessageDispatcher {

    ChannelDeliveryResult dispatch(String messageId, ChannelMessageRequest request);
}
