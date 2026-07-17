package com.lrj.platform.order;

import com.lrj.platform.protocol.order.OrderView;

import java.util.Optional;

/**
 * 订单只读查询的存储抽象（controller 依赖此接口，便于单测用 lambda 桩替换，
 * 对齐平台「接口 + 实现」的可测风格，不在 controller 里绑死具体 {@link JdbcOrderStore}）。
 */
public interface OrderStore {

    /** 按订单号查当前租户订单；查不到返回空。租户隔离在实现内按 {@code tenant_id} 完成。 */
    Optional<OrderView> findByOrderNo(String orderNo);
}
