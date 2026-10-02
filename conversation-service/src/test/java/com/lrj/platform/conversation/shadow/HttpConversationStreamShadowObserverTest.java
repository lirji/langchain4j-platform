package com.lrj.platform.conversation.shadow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.conversation.ConversationGenerationRequest;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.TenantContext;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class HttpConversationStreamShadowObserverTest {
    private final InternalToken tokens = new InternalToken("stream-candidate-test-internal-key-with-32-characters", Duration.ofMinutes(5));
    private final ConversationGenerationRequest request = new ConversationGenerationRequest("1", "hello", "context",
            new ConversationGenerationRequest.Style("English", "concise", "cite", ""), List.of());

    @Test void readerRequiresContiguousFramesOneTerminalAndBoundedText() throws Exception {
        try (var pool = Executors.newFixedThreadPool(1); var timer = new ScheduledThreadPoolExecutor(1)) {
            var observer = new HttpConversationStreamShadowObserver(HttpClient.newHttpClient(), URI.create("http://127.0.0.1/stream"),
                    new ObjectMapper(), tokens, "X-Internal-Token", pool, timer, Duration.ofSeconds(2), new ConversationShadowMetrics(null));
            String valid = event(0, "token", "hello") + event(1, "done", "");
            assertThat(observer.validateStream(bytes(valid))).isEqualTo("hello");
            for (String malformed : List.of(event(1, "token", "hello"), event(0, "token", ""),
                    event(0, "error", "failure"), event(0, "token", "hi"), valid + event(2, "done", ""),
                    event(0, "token", "x".repeat(65_537)) + event(1, "done", ""), "data: " + "x".repeat(262_145))) {
                assertThatThrownBy(() -> observer.validateStream(bytes(malformed))).isInstanceOf(IOException.class);
            }
        }
    }

    @Test void asynchronousHttpSseCarriesCapturedIdentityAndOnlyUpdatesComparisonMetrics() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var identity = new AtomicReference<TenantContext.Tenant>();
        server.createContext("/stream", exchange -> {
            identity.set(tokens.verify(exchange.getRequestHeaders().getFirst("X-Internal-Token")));
            exchange.getRequestBody().readAllBytes();
            byte[] payload = (event(0, "token", "hello") + event(1, "done", "")).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        var metrics = new SimpleMeterRegistry();
        try (var pool = Executors.newFixedThreadPool(1); var timer = new ScheduledThreadPoolExecutor(1)) {
            var observer = new HttpConversationStreamShadowObserver(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stream"), new ObjectMapper(),
                    tokens, "X-Internal-Token", pool, timer, Duration.ofSeconds(2), new ConversationShadowMetrics(metrics));
            var owner = new TenantContext.Tenant("acme", "alice", Set.of("chat"));
            TenantContext.set(owner);
            var session = observer.open(request);
            TenantContext.clear();
            session.finish("hello");
            await(() -> metrics.find("conversation.shadow.comparisons").tag("exact_match", "true").counter() != null);
            assertThat(identity.get()).isEqualTo(owner);
            assertThat(metrics.find("conversation.shadow.requests").tag("outcome", "stream_success").counter().count()).isOne();
            assertThat(TenantContext.captureRaw()).isNull();
        } finally { metrics.close(); server.stop(0); }
    }

    @Test void timeoutClosesStreamingBodyAndExecutorRejectionDoesNotThrowToPrimary() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stream", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try {
                exchange.getResponseBody().write(event(0, "token", "hello").getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                Thread.sleep(500);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        var metrics = new SimpleMeterRegistry();
        try (var pool = Executors.newFixedThreadPool(1); var timer = new ScheduledThreadPoolExecutor(1)) {
            var observer = new HttpConversationStreamShadowObserver(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stream"), new ObjectMapper(),
                    tokens, "X-Internal-Token", pool, timer, Duration.ofMillis(100), new ConversationShadowMetrics(metrics));
            TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
            observer.open(request).finish("hello");
            await(() -> metrics.find("conversation.shadow.requests").tag("outcome", "stream_timeout").counter() != null);
            pool.shutdownNow();
            assertThatCode(() -> observer.open(request)).doesNotThrowAnyException();
            assertThat(metrics.find("conversation.shadow.requests").tag("outcome", "stream_rejected").counter().count()).isOne();
            assertThat(metrics.find("conversation.shadow.comparisons").counter()).isNull();
        } finally { TenantContext.clear(); metrics.close(); server.stop(0); }
    }

    static String event(int sequence, String type, String text) throws IOException {
        return "data: " + new ObjectMapper().writeValueAsString(java.util.Map.of("sequence", sequence, "type", type, "data", text)) + "\n\n";
    }
    private static ByteArrayInputStream bytes(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }
    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
