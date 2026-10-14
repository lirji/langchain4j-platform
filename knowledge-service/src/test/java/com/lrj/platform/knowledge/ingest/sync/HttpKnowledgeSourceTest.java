package com.lrj.platform.knowledge.ingest.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class HttpKnowledgeSourceTest {
    private HttpServer server;
    private String body = "{\"items\":[{\"id\":\"faq1\",\"revision\":1,\"title\":\"FAQ\",\"content\":\"answer\"}],\"nextCursor\":\"next\"}";
    private int status = 200;
    private String query;
    private String token;
    private String internal;
    private final AtomicInteger redirected = new AtomicInteger();
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/feed", exchange -> {
            query = exchange.getRequestURI().getRawQuery(); token = exchange.getRequestHeaders().getFirst("Authorization");
            internal = exchange.getRequestHeaders().getFirst("X-Internal-Token");
            exchange.getResponseHeaders().add("Location", "/secret");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.createContext("/secret", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private KnowledgeSyncProperties.Source source() {
        return new KnowledgeSyncProperties.Source("faq", "tenant", "sync", "dept",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/feed"), "external-only", true);
    }
    @Test void mapsFeedAndEncodesOpaqueCursorWithoutForwardingInternalIdentity() {
        var result = new HttpKnowledgeSource(new ObjectMapper()).fetch(source(), "x&limit=900");
        assertThat(result.items()).hasSize(1); assertThat(result.nextCursor()).isEqualTo("next");
        assertThat(query).isEqualTo("limit=25&cursor=x%26limit%3D900");
        assertThat(token).isEqualTo("Bearer external-only"); assertThat(internal).isNull();
        assertThat(source().toString()).doesNotContain("external-only", "127.0.0.1");
    }
    @Test void rejectsRedirectWithoutFollowingIt() {
        status = 302;
        assertThatThrownBy(() -> new HttpKnowledgeSource(new ObjectMapper()).fetch(source(), ""))
                .hasMessage("SOURCE_HTTP_ERROR");
        assertThat(redirected).hasValue(0);
    }
    @Test void rejectsOversizedResponse() {
        body = "x".repeat(1_048_577);
        assertThatThrownBy(() -> new HttpKnowledgeSource(new ObjectMapper()).fetch(source(), "")).hasMessage("SOURCE_TOO_LARGE");
    }
    @Test void rejectsBadQualityAndAcceptsExplicitTombstone() {
        var client = new HttpKnowledgeSource(new ObjectMapper());
        body = "{\"items\":[{\"id\":\"faq\",\"revision\":1,\"title\":\"Q\",\"content\":\" \"}],\"nextCursor\":\"next\"}";
        assertThatThrownBy(() -> client.fetch(source(), "")).hasMessage("INVALID_ITEM");
        body = "{\"items\":[{\"id\":\"faq\",\"revision\":2,\"deleted\":true}],\"nextCursor\":\"next\"}";
        assertThat(client.fetch(source(), "").items().getFirst().deleted()).isTrue();
    }
    @Test void rejectsStalledCursorDuplicateIdsAndFractionalRevision() {
        var client = new HttpKnowledgeSource(new ObjectMapper());
        assertThatThrownBy(() -> client.fetch(source(), "next")).hasMessage("INVALID_PAGE");
        body = body.replace("\"revision\":1", "\"revision\":1.5");
        assertThatThrownBy(() -> client.fetch(source(), "")).hasMessage("SOURCE_UNAVAILABLE_OR_INVALID");
        body = "{\"items\":[{\"id\":\"faq\",\"revision\":1,\"deleted\":true},{\"id\":\"faq\",\"revision\":2,\"deleted\":true}],\"nextCursor\":\"next\"}";
        assertThatThrownBy(() -> client.fetch(source(), "")).hasMessage("INVALID_ITEM");
    }
    @Test void endpointRequiresExplicitInsecureOptInAndRejectsEmbeddedCredentials() {
        var base = source();
        assertThatThrownBy(() -> new KnowledgeSyncProperties.Source(base.id(), base.tenantId(), base.userId(), base.department(),
                base.endpoint(), "", false).validate()).isInstanceOf(IllegalArgumentException.class);
        for (String uri : java.util.List.of("file:///tmp/feed", "https://user:secret@example.invalid/feed", "https://example.invalid/feed?q=x"))
            assertThatThrownBy(() -> new KnowledgeSyncProperties.Source("faq", "tenant", "sync", null, URI.create(uri), "", false).validate())
                    .isInstanceOf(IllegalArgumentException.class);
    }
}
