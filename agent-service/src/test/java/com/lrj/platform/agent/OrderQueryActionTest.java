package com.lrj.platform.agent;

import com.lrj.platform.agent.actions.OrderQueryAction;
import com.lrj.platform.agent.client.OrderClient;
import com.lrj.platform.protocol.order.OrderView;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OrderQueryAction 纯单测：桩 {@link OrderClient}，验证渲染/错误/边界，不起 Spring、不碰网络。
 */
class OrderQueryActionTest {

    @Test
    void rendersOrderDetail() {
        OrderQueryAction action = new OrderQueryAction(
                no -> new OrderClient.Outcome(
                        new OrderView("101", "张三", "1200.00", "已支付", "2026-05-03"), null));

        String output = action.run("101");

        assertThat(output).contains("订单号: 101");
        assertThat(output).contains("状态: 已支付");
        assertThat(output).contains("金额: ¥1200.00");
        assertThat(output).contains("客户: 张三");
        assertThat(output).contains("下单日期: 2026-05-03");
    }

    @Test
    void reportsNotFoundWhenOrderNull() {
        OrderQueryAction action = new OrderQueryAction(
                no -> new OrderClient.Outcome(null, null));

        assertThat(action.run("999")).contains("未找到订单 999");
    }

    @Test
    void reportsErrorText() {
        OrderQueryAction action = new OrderQueryAction(
                no -> new OrderClient.Outcome(null, "订单不存在: 999"));

        assertThat(action.run("999")).contains("查询失败").contains("订单不存在: 999");
    }

    @Test
    void reportsDisabledFromNoopClient() {
        OrderQueryAction action = new OrderQueryAction(
                no -> new OrderClient.Outcome(null, "order lookup disabled"));

        assertThat(action.run("101")).contains("查询失败").contains("order lookup disabled");
    }

    @Test
    void rejectsBlankInput() {
        OrderQueryAction action = new OrderQueryAction(
                no -> { throw new AssertionError("client should not be called for blank input"); });

        assertThat(action.run("  ")).contains("订单号为空");
    }
}
