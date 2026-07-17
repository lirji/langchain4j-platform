package com.lrj.platform.order;

import com.lrj.platform.protocol.order.OrderView;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OrderController 纯单测：用 lambda 桩 {@link OrderStore}（不 mock 具体类，对齐仓库接口桩惯例），
 * 验证命中→200、未命中→404。不起 Spring context、不碰 DB。租户隔离由 store 层集成测覆盖。
 */
class OrderControllerTest {

    @Test
    void returns200WithOrderWhenFound() {
        OrderView view = new OrderView("101", "张三", "1200.00", "已支付", "2026-05-03");
        OrderController controller = new OrderController(no -> Optional.of(view));

        ResponseEntity<OrderView> resp = controller.getOrder("101");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo(view);
    }

    @Test
    void returns404WhenNotFound() {
        OrderController controller = new OrderController(no -> Optional.empty());

        ResponseEntity<OrderView> resp = controller.getOrder("999");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).isNull();
    }
}
