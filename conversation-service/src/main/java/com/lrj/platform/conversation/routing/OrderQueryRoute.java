package com.lrj.platform.conversation.routing;

import com.lrj.platform.protocol.order.OrderView;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 订单查询的确定性路由：先识别订单意图并提取订单号，再调用权威订单服务，避免让 LLM 猜测业务数据。
 */
@Component
public class OrderQueryRoute {

    private static final Pattern ORDER_HINT = Pattern.compile("订单|\\border\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOOKUP_HINT = Pattern.compile(
            "查询|查一下|查找|状态|金额|客户|下单|退款|发货|取消|详情|情况|进度|到哪|"
                    + "\\b(?:query|find|status|amount|customer|refund|ship|detail|track)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCUMENTATION_HINT = Pattern.compile(
            "本项目|文档|手册|配置|代码|实现|设计|接口|数据库|表结构|服务架构",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_ORDER_NO = Pattern.compile(
            "(?:订单号|order\\s*(?:no\\.?|number|id))\\s*(?:是|为|[:：#=])?\\s*"
                    + "([A-Za-z0-9][A-Za-z0-9_-]{0,63})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ORDER_BEFORE_NO = Pattern.compile(
            "(?:订单|order)\\s*(?:是|为|[:：#=])?\\s*([A-Za-z0-9][A-Za-z0-9_-]{0,63})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NO_BEFORE_ORDER = Pattern.compile(
            "([A-Za-z0-9][A-Za-z0-9_-]{0,63})\\s*(?:号)?\\s*(?:订单|order)",
            Pattern.CASE_INSENSITIVE);

    private final OrderLookupClient orders;

    public OrderQueryRoute(OrderLookupClient orders) {
        this.orders = orders;
    }

    public boolean matches(String message) {
        if (!orders.enabled() || message == null || !ORDER_HINT.matcher(message).find()) {
            return false;
        }
        if (extractOrderNo(message).isPresent()) {
            return true;
        }
        return !DOCUMENTATION_HINT.matcher(message).find() && LOOKUP_HINT.matcher(message).find();
    }

    public String query(String message) {
        Optional<String> parsed = extractOrderNo(message);
        if (parsed.isEmpty()) {
            return "请提供要查询的订单号，例如：查询订单 204。";
        }
        String orderNo = parsed.get();
        OrderLookupClient.Outcome outcome = orders.getByNo(orderNo);
        if (outcome.error() != null) {
            return "订单查询失败：" + outcome.error() + "。";
        }
        OrderView order = outcome.order();
        if (order == null) {
            return "未找到订单 " + orderNo + "。";
        }
        StringBuilder reply = new StringBuilder("订单号：").append(order.orderNo());
        reply.append("\n状态：").append(order.status());
        if (order.amount() != null) {
            reply.append("\n金额：¥").append(order.amount());
        }
        if (order.customer() != null && !order.customer().isBlank()) {
            reply.append("\n客户：").append(order.customer());
        }
        if (order.createdAt() != null && !order.createdAt().isBlank()) {
            reply.append("\n下单日期：").append(order.createdAt());
        }
        return reply.toString();
    }

    static Optional<String> extractOrderNo(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }
        Optional<String> explicit = firstGroup(EXPLICIT_ORDER_NO, message, false);
        if (explicit.isPresent()) {
            return explicit;
        }
        Optional<String> after = firstGroup(ORDER_BEFORE_NO, message, true);
        return after.isPresent() ? after : firstGroup(NO_BEFORE_ORDER, message, true);
    }

    private static Optional<String> firstGroup(Pattern pattern, String message, boolean requireDigit) {
        Matcher matcher = pattern.matcher(message);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (!requireDigit || candidate.chars().anyMatch(Character::isDigit)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
