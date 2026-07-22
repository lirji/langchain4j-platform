package com.lrj.platform.conversation.routing;

import com.lrj.platform.protocol.order.OrderView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/** 通过带租户/trace 转发器的 RestTemplate 查询 order-service。 */
@Component
@ConditionalOnProperty(
        name = "app.conversation.router.order.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class HttpOrderLookupClient implements OrderLookupClient {

    private static final Logger log = LoggerFactory.getLogger(HttpOrderLookupClient.class);

    private final RestTemplate restTemplate;

    public HttpOrderLookupClient(@Qualifier("orderRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public Outcome getByNo(String orderNo) {
        try {
            OrderView order = restTemplate.getForObject("/orders/{orderNo}", OrderView.class, orderNo);
            return new Outcome(order, order == null ? "订单服务返回空响应" : null);
        } catch (HttpClientErrorException.NotFound notFound) {
            return new Outcome(null, null);
        } catch (RestClientException ex) {
            log.warn("routed order lookup failed: {}", ex.toString());
            return new Outcome(null, "订单服务暂时不可用，请稍后再试");
        }
    }
}
