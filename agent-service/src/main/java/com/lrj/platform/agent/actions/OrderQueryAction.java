package com.lrj.platform.agent.actions;

import com.lrj.platform.agent.AgentAction;
import com.lrj.platform.agent.client.OrderClient;
import com.lrj.platform.protocol.order.OrderView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 按订单号查订单的 agent 动作。只读、无副作用，故与 rag_search/analytics_sql 一样在 {@code app.agent.enabled}
 * 时即挂载；真正是否可用取决于注入的 {@link OrderClient}（Http 需 {@code app.agent.order.enabled=true} 且
 * order-service 可达，否则 Noop 返回「disabled」）。租户随内部 JWT 透传，order-service 按租户隔离，本动作无需关心。
 */
@Component
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true", matchIfMissing = true)
public class OrderQueryAction implements AgentAction {

    private final OrderClient orders;

    public OrderQueryAction(OrderClient orders) {
        this.orders = orders;
    }

    @Override
    public String name() {
        return "order_query";
    }

    @Override
    public String description() {
        return "按订单号查订单详情（状态/金额/客户/下单日期）；actionInput 填订单号（如 101）。"
                + "只读、不做任何修改。业务统计类问题（总额/趋势/top-N）用 analytics_sql，不要用本动作。";
    }

    @Override
    public String run(String input) {
        if (input == null || input.isBlank()) {
            return "订单号为空：actionInput 请填要查的订单号。";
        }
        OrderClient.Outcome outcome = orders.getByNo(input.trim());
        if (outcome.error() != null) {
            return "查询失败：" + outcome.error();
        }
        OrderView o = outcome.order();
        if (o == null) {
            return "未找到订单 " + input.trim();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("订单号: ").append(o.orderNo());
        sb.append("\n状态: ").append(o.status());
        if (o.amount() != null) {
            sb.append("\n金额: ¥").append(o.amount());
        }
        if (o.customer() != null && !o.customer().isBlank()) {
            sb.append("\n客户: ").append(o.customer());
        }
        if (o.createdAt() != null) {
            sb.append("\n下单日期: ").append(o.createdAt());
        }
        return sb.toString();
    }
}
