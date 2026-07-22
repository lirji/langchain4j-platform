package com.lrj.platform.conversation.routing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 关闭订单路由时的显式降级实现，保证其它 RAG/CHAT 路由仍可使用。 */
@Component
@ConditionalOnProperty(name = "app.conversation.router.order.enabled", havingValue = "false")
public class NoopOrderLookupClient implements OrderLookupClient {

    @Override
    public Outcome getByNo(String orderNo) {
        return new Outcome(null, "订单查询能力未启用");
    }

    @Override
    public boolean enabled() {
        return false;
    }
}
