package com.lrj.platform.protocol.order;

/**
 * 订单详情视图。跨服务 DTO：order-service 的 {@code GET /orders/{orderNo}} 响应体，
 * agent-service 的 {@code order_query} 动作经 {@code OrderClient} 反序列化同一契约。
 *
 * <p>租户身份随内部 JWT 传播、不进 body；order-service 已按当前租户过滤，返回的都是本租户订单。
 * 金额渲染成字符串（如 {@code "1200.00"}）而非 BigDecimal，避免跨服务 JSON 数值精度歧义，
 * 也让动作层直接拼中文文本喂回模型。
 *
 * @param orderNo   订单号（业务主键）
 * @param customer  下单客户名（可空——订单存在但客户记录缺失时为 null）
 * @param amount    订单金额，单位元，字符串形式（如 "1200.00"）
 * @param status    订单状态（中文枚举）：已支付 / 已发货 / 已取消 / 已退款
 * @param createdAt 下单日期（如 "2026-05-03"）
 */
public record OrderView(String orderNo,
                        String customer,
                        String amount,
                        String status,
                        String createdAt) {
}
