package com.lrj.platform.metering.budget;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.gateway.GatewayChatModelFactory;
import com.lrj.platform.gateway.GatewayClientProperties;
import com.lrj.platform.metering.InMemoryTokenBudgetTracker;
import com.lrj.platform.metering.TokenBudgetChatModelListener;
import com.lrj.platform.metering.TokenBudgetProperties;
import com.lrj.platform.security.TenantContext;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** 使用本地假 OpenAI HTTP/SSE，验证所有工厂出口准入且真实 listener 不重复扣账。 */
class BudgetGatewayHttpIntegrationTest {
    @Test void everyFactoryExitUsesSingleLedgerAndStreamingUsage() throws Exception {
        var mapper = new ObjectMapper();
        var bodies = new java.util.concurrent.ConcurrentLinkedQueue<com.fasterxml.jackson.databind.JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var body = mapper.readTree(exchange.getRequestBody()); bodies.add(body);
            String data;
            if (body.path("stream").asBoolean()) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                data = """
                        data: {"id":"it","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":"answer"}}]}

                        data: {"id":"it","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}

                        data: [DONE]

                        """;
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                data = """
                        {"id":"it","object":"chat.completion","model":"fake","choices":[{"index":0,"message":{"role":"assistant","content":"answer"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}
                        """;
            }
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var budget = new TokenBudgetProperties(); budget.setTimezone("UTC");
            var tracker = new InMemoryTokenBudgetTracker(budget);
            var decorator = new BudgetModelDecorator(new InMemoryBudgetLedger(tracker, budget, Clock.systemUTC()),
                    new ReservationBudgetProperties());
            var gateway = new GatewayClientProperties();
            gateway.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            gateway.setApiKey("local-fake-key"); gateway.setTimeout(Duration.ofSeconds(5)); gateway.setMaxRetries(0);
            var factory = new GatewayChatModelFactory(gateway, List.of(new TokenBudgetChatModelListener(tracker)),
                    () -> "t1", null, List.of(decorator));
            TenantContext.set(new TenantContext.Tenant("t1", "u1", Set.of("chat")));
            for (var model : List.of(factory.build(), factory.buildJsonMode(), factory.buildDeterministic(), factory.build("alternate", 0.0)))
                assertThat(model.chat("hello")).isEqualTo("answer");
            assertThat(tracker.currentUsed("t1")).isEqualTo(120);
            var finished = new CompletableFuture<ChatResponse>();
            factory.buildStreaming().chat(ChatRequest.builder().messages(UserMessage.from("hello")).build(),
                    new StreamingChatResponseHandler() {
                        @Override public void onCompleteResponse(ChatResponse response) { finished.complete(response); }
                        @Override public void onError(Throwable error) { finished.completeExceptionally(error); }
                    });
            TenantContext.clear();
            assertThat(finished.get(10, TimeUnit.SECONDS).aiMessage().text()).isEqualTo("answer");
            assertThat(tracker.currentUsed("t1")).isEqualTo(150);
            assertThat(bodies).hasSize(5);
            assertThat(bodies.stream().filter(b -> b.path("stream").asBoolean()).findFirst().orElseThrow()
                    .path("stream_options").path("include_usage").asBoolean()).isTrue();
            assertThat(bodies.stream().allMatch(b -> b.path("max_tokens").asInt() == 4096
                    || b.path("max_completion_tokens").asInt() == 4096)).isTrue();
        } finally { TenantContext.clear(); server.stop(0); }
    }
}
