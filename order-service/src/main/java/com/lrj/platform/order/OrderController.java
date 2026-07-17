package com.lrj.platform.order;

import com.lrj.platform.protocol.order.OrderView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单只读查询端点。{@code GET /orders/{orderNo}} → 命中返回 {@link OrderView}，未命中 404。
 *
 * <p>只读接口不设 scope 门禁（对齐 vision/analytics 只读风格）：任何过了边缘鉴权的合法租户即可查。
 * controller 不感知租户 —— 租户隔离沿用过滤器链注入的 {@code TenantContext}，由 {@link JdbcOrderStore}
 * 在 SQL 层按 {@code tenant_id} 过滤（别的租户查同一订单号得 404）。
 */
@RestController
public class OrderController {

    private final OrderStore store;

    public OrderController(OrderStore store) {
        this.store = store;
    }

    @GetMapping("/orders/{orderNo}")
    public ResponseEntity<OrderView> getOrder(@PathVariable String orderNo) {
        return store.findByOrderNo(orderNo)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
