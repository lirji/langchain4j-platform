package com.lrj.platform.conversation.routing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class OrderLookupClientWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(
                    HttpOrderLookupClient.class,
                    NoopOrderLookupClient.class,
                    StubRestTemplateConfig.class);

    @Test
    void enabledByDefaultUsesHttpClient() {
        runner.run(context -> assertThat(context)
                .hasSingleBean(OrderLookupClient.class)
                .getBean(OrderLookupClient.class)
                .isInstanceOf(HttpOrderLookupClient.class));
    }

    @Test
    void disabledUsesNoopAndDoesNotClaimOrderIntent() {
        runner.withPropertyValues("app.conversation.router.order.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(OrderLookupClient.class)
                    .getBean(OrderLookupClient.class)
                    .isInstanceOf(NoopOrderLookupClient.class);
            assertThat(context).doesNotHaveBean(HttpOrderLookupClient.class);
            OrderQueryRoute route = new OrderQueryRoute(context.getBean(OrderLookupClient.class));
            assertThat(route.matches("查询退款订单 204")).isFalse();
        });
    }

    @Configuration
    static class StubRestTemplateConfig {

        @Bean
        RestTemplate orderRestTemplate() {
            return new RestTemplate();
        }
    }
}
