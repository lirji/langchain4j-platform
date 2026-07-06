package com.lrj.platform.eval;

import com.lrj.platform.protocol.eval.EvalCase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EvalRunnerTest {

    @Test
    void executesPostCaseAndPassesWhenExpectedTextIsPresent() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        EvalProperties properties = new EvalProperties();
        properties.setApiKey("dev-key");
        EvalRunner runner = new EvalRunner(restTemplate, properties);

        server.expect(once(), requestTo("http://edge.local/chat"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-Key", "dev-key"))
                .andRespond(withSuccess("{\"answer\":\"hello\"}", MediaType.APPLICATION_JSON));

        var result = runner.execute("http://edge.local", new EvalCase(
                "chat-smoke",
                "/chat",
                "POST",
                Map.of("message", "hi"),
                "hello"));

        assertThat(result.passed()).isTrue();
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.responseSnippet()).contains("hello");
        server.verify();
    }

    @Test
    void failsWhenExpectedTextIsMissing() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        EvalRunner runner = new EvalRunner(restTemplate, new EvalProperties());

        server.expect(once(), requestTo("http://edge.local/chat"))
                .andRespond(withSuccess("{\"answer\":\"other\"}", MediaType.APPLICATION_JSON));

        var result = runner.execute("http://edge.local", new EvalCase(
                "chat-smoke",
                "/chat",
                "POST",
                Map.of("message", "hi"),
                "hello"));

        assertThat(result.passed()).isFalse();
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.error()).isEqualTo("response did not contain expected text");
        server.verify();
    }

    @Test
    void passesWhenOracleTextIsPresent() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        EvalRunner runner = new EvalRunner(restTemplate, new EvalProperties());

        server.expect(once(), requestTo("http://edge.local/chat"))
                .andRespond(withSuccess("{\"answer\":\"monolith-compatible\"}", MediaType.APPLICATION_JSON));

        var result = runner.execute("http://edge.local", new EvalCase(
                "chat-oracle",
                "/chat",
                "POST",
                Map.of("message", "hi"),
                "compatible",
                "monolith-compatible"));

        assertThat(result.passed()).isTrue();
        assertThat(result.oracleMatched()).isTrue();
        assertThat(result.oracleExpected()).isNull();
        server.verify();
    }

    @Test
    void failsWhenOracleTextIsMissing() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        EvalRunner runner = new EvalRunner(restTemplate, new EvalProperties());

        server.expect(once(), requestTo("http://edge.local/chat"))
                .andRespond(withSuccess("{\"answer\":\"new-service\"}", MediaType.APPLICATION_JSON));

        var result = runner.execute("http://edge.local", new EvalCase(
                "chat-oracle",
                "/chat",
                "POST",
                Map.of("message", "hi"),
                null,
                "monolith-compatible"));

        assertThat(result.passed()).isFalse();
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.error()).isEqualTo("response did not match monolith oracle");
        assertThat(result.oracleMatched()).isFalse();
        assertThat(result.oracleExpected()).isEqualTo("monolith-compatible");
        server.verify();
    }

    @Test
    void keepsLegacyResultConstructorOracleNeutral() {
        var result = new com.lrj.platform.protocol.eval.EvalCaseResult("case-1", true, 200, null, "ok", 1);

        assertThat(result.oracleMatched()).isTrue();
        assertThat(result.oracleExpected()).isNull();
    }

    @Test
    void capturesHttpErrorResponse() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        EvalRunner runner = new EvalRunner(restTemplate, new EvalProperties());

        server.expect(once(), requestTo("http://edge.local/chat"))
                .andRespond(withServerError().body("down"));

        var result = runner.execute("http://edge.local", new EvalCase(
                "chat-smoke",
                "/chat",
                "GET",
                Map.of(),
                null));

        assertThat(result.passed()).isFalse();
        assertThat(result.status()).isEqualTo(500);
        assertThat(result.error()).isEqualTo("target returned HTTP 500");
        assertThat(result.responseSnippet()).isEqualTo("down");
        server.verify();
    }

    @Test
    void validatesCaseBeforeSendingRequest() {
        EvalRunner runner = new EvalRunner(new RestTemplate(), new EvalProperties());

        var result = runner.execute("http://edge.local", new EvalCase(
                "",
                "/chat",
                "GET",
                Map.of(),
                null));

        assertThat(result.passed()).isFalse();
        assertThat(result.status()).isZero();
        assertThat(result.error()).isEqualTo("id is required");
    }
}
