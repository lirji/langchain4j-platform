package com.lrj.platform.agent.client;

import com.lrj.platform.protocol.order.OrderView;

/**
 * agent→order-service 客户端抽象。供 {@code order_query} 动作按订单号查订单。
 * 默认实现 {@link HttpOrderClient}（{@code app.agent.order.enabled=true} 时）；
 * 关闭时 {@link NoopOrderClient} 兜底。错误进返回值（{@code error} 非空）而非抛异常，避免打断 ReAct 循环。
 */
public interface OrderClient {

    /** 按订单号查订单；查不到时 order 为 null、error 说明原因。 */
    Outcome getByNo(String orderNo);

    record Outcome(OrderView order, String error) {
    }
}
