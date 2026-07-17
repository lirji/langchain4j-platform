package com.lrj.platform.agent.client;

import com.lrj.platform.protocol.order.OrderView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * {@link OrderClient} 的 HTTP 实现，经 {@code orderRestTemplate}（透传租户与 traceId）调 order-service 的
 * {@code GET /orders/{orderNo}}。404 归一为「订单不存在」，其它 RestClient 异常降级为带 error 的结果，不上抛。
 *
 * <p>门控 {@code app.agent.enabled && app.agent.order.enabled}（默认关）：只有显式开且 order-service 可达时才装配。
 * 用 {@code @ConditionalOnExpression}（而非多名 {@code @ConditionalOnProperty}）以正确处理
 * {@code app.agent.enabled} 缺省即 true 的语义，与 {@link NoopOrderClient} 精确二选一。
 */
@Component
@ConditionalOnExpression("${app.agent.enabled:true} and ${app.agent.order.enabled:false}")
public class HttpOrderClient implements OrderClient {

    private static final Logger log = LoggerFactory.getLogger(HttpOrderClient.class);

    private final RestTemplate restTemplate;

    public HttpOrderClient(@Qualifier("orderRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public Outcome getByNo(String orderNo) {
        try {
            OrderView view = restTemplate.getForObject("/orders/{no}", OrderView.class, orderNo);
            return new Outcome(view, view == null ? "empty order response" : null);
        } catch (HttpClientErrorException.NotFound nf) {
            return new Outcome(null, "订单不存在: " + orderNo);
        } catch (RestClientException ex) {
            log.warn("agent order lookup failed: {}", ex.toString());
            return new Outcome(null, ex.getMessage());
        }
    }
}
