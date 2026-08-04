package com.lrj.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RestTemplatePoolingTest {

    @Test
    void bootBuilderSelectsApacheConnectionPoolingAndKeepsConfiguredTimeouts() {
        RestTemplate template = new RestTemplateBuilder()
                .setConnectTimeout(Duration.ofMillis(200))
                .setReadTimeout(Duration.ofMillis(500))
                .build();

        assertThat(template.getRequestFactory())
                .isInstanceOf(HttpComponentsClientHttpRequestFactory.class);
    }

    @Test
    void autoConfiguredBuilderAppliesOneSharedResilienceInterceptor() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PlatformSecurityAutoConfiguration.class,
                        RestTemplateAutoConfiguration.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    RestTemplateBuilder builder = context.getBean(RestTemplateBuilder.class);
                    OutboundHttpResilienceInterceptor shared =
                            context.getBean(OutboundHttpResilienceInterceptor.class);

                    RestTemplate first = builder.build();
                    RestTemplate second = builder.build();

                    assertThat(first.getInterceptors())
                            .filteredOn(OutboundHttpResilienceInterceptor.class::isInstance)
                            .containsExactly(shared);
                    assertThat(second.getInterceptors())
                            .filteredOn(OutboundHttpResilienceInterceptor.class::isInstance)
                            .containsExactly(shared);
                });
    }
}
