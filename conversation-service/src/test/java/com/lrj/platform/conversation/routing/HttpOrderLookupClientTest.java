package com.lrj.platform.conversation.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpOrderLookupClientTest {

    private MockRestServiceServer server;
    private HttpOrderLookupClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplateBuilder().rootUri("http://orders.test").build();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new HttpOrderLookupClient(restTemplate);
    }

    @Test
    void returnsOrderOnSuccess() {
        server.expect(once(), requestTo("http://orders.test/orders/204"))
                .andRespond(withSuccess("""
                        {"orderNo":"204","customer":"王五","amount":"450.00",
                         "status":"已退款","createdAt":"2026-05-12"}
                        """, MediaType.APPLICATION_JSON));

        OrderLookupClient.Outcome outcome = client.getByNo("204");

        assertThat(outcome.error()).isNull();
        assertThat(outcome.order()).isNotNull();
        assertThat(outcome.order().status()).isEqualTo("已退款");
        server.verify();
    }

    @Test
    void maps404ToNotFound() {
        server.expect(once(), requestTo("http://orders.test/orders/999"))
                .andRespond(withResourceNotFound());

        OrderLookupClient.Outcome outcome = client.getByNo("999");

        assertThat(outcome.order()).isNull();
        assertThat(outcome.error()).isNull();
        server.verify();
    }

    @Test
    void hidesTransportDetailsOnFailure() {
        server.expect(once(), requestTo("http://orders.test/orders/204"))
                .andRespond(withServerError());

        OrderLookupClient.Outcome outcome = client.getByNo("204");

        assertThat(outcome.order()).isNull();
        assertThat(outcome.error()).isEqualTo("订单服务暂时不可用，请稍后再试");
        server.verify();
    }
}
