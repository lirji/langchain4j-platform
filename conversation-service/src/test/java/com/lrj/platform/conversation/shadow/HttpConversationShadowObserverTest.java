package com.lrj.platform.conversation.shadow;

import com.lrj.platform.protocol.conversation.ConversationGenerationRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.RejectedExecutionException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpConversationShadowObserverTest {

    private static final ConversationGenerationRequest REQUEST =
            new ConversationGenerationRequest(
                    "1",
                    "hello",
                    "knowledge",
                    new ConversationGenerationRequest.Style("中文", "简洁", "cite", ""),
                    List.of());

    @Test
    void postsIdentityFreeContractAndOnlyRecordsComparison() {
        RestTemplate restTemplate = new RestTemplateBuilder()
                .rootUri("http://candidate")
                .build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), requestTo("http://candidate/internal/conversation/generate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "schema_version":"1",
                          "message":"hello",
                          "context":"knowledge",
                          "style":{
                            "language":"中文",
                            "tone":"简洁",
                            "citation_policy":"cite",
                            "extra":""
                          },
                          "history":[]
                        }
                        """, true))
                .andRespond(withSuccess("{\"reply\":\"candidate\"}", MediaType.APPLICATION_JSON));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HttpConversationShadowObserver observer = new HttpConversationShadowObserver(
                restTemplate, Runnable::run, new ConversationShadowMetrics(registry));

        observer.observe(REQUEST, "primary");

        server.verify();
        assertThat(registry.get("conversation.shadow.requests")
                .tag("outcome", "success").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("conversation.shadow.comparisons")
                .tag("exact_match", "false").counter().count()).isEqualTo(1.0);
    }

    @Test
    void candidateFailureCannotEscapeIntoPrimaryPath() {
        RestTemplate restTemplate = new RestTemplateBuilder()
                .rootUri("http://candidate")
                .build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://candidate/internal/conversation/generate"))
                .andRespond(withServerError());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HttpConversationShadowObserver observer = new HttpConversationShadowObserver(
                restTemplate, Runnable::run, new ConversationShadowMetrics(registry));

        assertThatCode(() -> observer.observe(REQUEST, "primary")).doesNotThrowAnyException();

        assertThat(registry.get("conversation.shadow.requests")
                .tag("outcome", "failure").counter().count()).isEqualTo(1.0);
    }

    @Test
    void saturatedExecutorCannotFailPrimaryRequest() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HttpConversationShadowObserver observer = new HttpConversationShadowObserver(
                new RestTemplate(),
                task -> {
                    throw new RejectedExecutionException("full");
                },
                new ConversationShadowMetrics(registry));

        assertThatCode(() -> observer.observe(REQUEST, "primary")).doesNotThrowAnyException();

        assertThat(registry.get("conversation.shadow.requests")
                .tag("outcome", "rejected").counter().count()).isEqualTo(1.0);
    }
}
