package com.lrj.platform.conversation.routing;

import com.lrj.platform.protocol.order.OrderView;

/**
 * 意图路由访问订单服务的最小客户端契约。错误进入 {@link Outcome}，避免下游短暂不可用时打断路由请求。
 */
public interface OrderLookupClient {

    Outcome getByNo(String orderNo);

    default boolean enabled() {
        return true;
    }

    record Outcome(OrderView order, String error) {
    }
}
