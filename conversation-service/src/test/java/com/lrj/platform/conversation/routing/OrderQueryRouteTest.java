package com.lrj.platform.conversation.routing;

import com.lrj.platform.protocol.order.OrderView;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OrderQueryRouteTest {

    @Test
    void queriesRefundOrderAndRendersAuthoritativeFields() {
        AtomicReference<String> requested = new AtomicReference<>();
        OrderQueryRoute route = new OrderQueryRoute(orderNo -> {
            requested.set(orderNo);
            return new OrderLookupClient.Outcome(
                    new OrderView("204", "王五", "450.00", "已退款", "2026-05-12"), null);
        });

        assertThat(route.matches("查询退款订单 204")).isTrue();
        assertThat(route.query("查询退款订单 204"))
                .contains("订单号：204")
                .contains("状态：已退款")
                .contains("金额：¥450.00")
                .contains("客户：王五")
                .contains("下单日期：2026-05-12");
        assertThat(requested.get()).isEqualTo("204");
    }

    @Test
    void extractsCommonChineseAndEnglishOrderNumberForms() {
        assertThat(OrderQueryRoute.extractOrderNo("订单号为 O-204 的状态")).contains("O-204");
        assertThat(OrderQueryRoute.extractOrderNo("204号订单退款了吗")).contains("204");
        assertThat(OrderQueryRoute.extractOrderNo("track order #A_204")).contains("A_204");
    }

    @Test
    void asksForOrderNumberWithoutCallingClient() {
        OrderQueryRoute route = new OrderQueryRoute(orderNo -> {
            throw new AssertionError("client must not be called without an order number");
        });

        assertThat(route.matches("帮我查询订单状态")).isTrue();
        assertThat(route.query("帮我查询订单状态")).contains("请提供").contains("订单号");
    }

    @Test
    void doesNotHijackDocumentationQuestion() {
        OrderQueryRoute route = new OrderQueryRoute(orderNo -> new OrderLookupClient.Outcome(null, null));

        assertThat(route.matches("本项目的订单服务怎么配置？")).isFalse();
        assertThat(route.matches("订单状态字段的代码如何实现？")).isFalse();
    }

    @Test
    void disabledOrderRouteFallsBackToLlmRouter() {
        OrderQueryRoute route = new OrderQueryRoute(new NoopOrderLookupClient());

        assertThat(route.matches("查询退款订单 204")).isFalse();
    }

    @Test
    void reportsNotFoundAndUnavailableDistinctly() {
        OrderQueryRoute missing = new OrderQueryRoute(
                orderNo -> new OrderLookupClient.Outcome(null, null));
        OrderQueryRoute unavailable = new OrderQueryRoute(
                orderNo -> new OrderLookupClient.Outcome(null, "订单服务暂时不可用，请稍后再试"));

        assertThat(missing.query("查询订单 999")).isEqualTo("未找到订单 999。");
        assertThat(unavailable.query("查询订单 999"))
                .isEqualTo("订单查询失败：订单服务暂时不可用，请稍后再试。");
    }
}
