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
    void passesWhenJsonPathAssertionsMatch() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        EvalRunner runner = new EvalRunner(restTemplate, new EvalProperties());

        server.expect(once(), requestTo("http://edge.local/chat"))
                .andRespond(withSuccess("{\"answer\":\"ok\",\"items\":[{\"name\":\"refund\"}]}", MediaType.APPLICATION_JSON));

        var result = runner.execute("http://edge.local", new EvalCase(
                "json-path",
                "/chat",
                "GET",
                Map.of(),
                null,
                null,
                Map.of("$.answer", "ok", "$.items[0].name", "refund")));

        assertThat(result.passed()).isTrue();
        server.verify();
    }

    @Test
    void failsWhenJsonPathAssertionDoesNotMatch() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        EvalRunner runner = new EvalRunner(restTemplate, new EvalProperties());

        server.expect(once(), requestTo("http://edge.local/chat"))
                .andRespond(withSuccess("{\"answer\":\"other\"}", MediaType.APPLICATION_JSON));

        var result = runner.execute("http://edge.local", new EvalCase(
                "json-path",
                "/chat",
                "GET",
                Map.of(),
                null,
                null,
                Map.of("$.answer", "ok")));

        assertThat(result.passed()).isFalse();
        assertThat(result.error()).isEqualTo("json path assertion failed: $.answer");
        server.verify();
    }

    @Test
    void failsJsonPathAssertionsWhenResponseIsNotJson() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        EvalRunner runner = new EvalRunner(restTemplate, new EvalProperties());

        server.expect(once(), requestTo("http://edge.local/chat"))
                .andRespond(withSuccess("plain text", MediaType.TEXT_PLAIN));

        var result = runner.execute("http://edge.local", new EvalCase(
                "json-path",
                "/chat",
                "GET",
                Map.of(),
                null,
                null,
                Map.of("$.answer", "ok")));

        assertThat(result.passed()).isFalse();
        assertThat(result.error()).isEqualTo("response was not valid JSON");
        server.verify();
    }

    @Test
    void passesWhenSemanticSimilarityIsAboveThreshold() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        EvalRunner runner = new EvalRunner(restTemplate, new EvalProperties());

        server.expect(once(), requestTo("http://edge.local/chat"))
                .andRespond(withSuccess("refund policy allows manual approval for high risk refunds", MediaType.TEXT_PLAIN));

        var result = runner.execute("http://edge.local", new EvalCase(
                "semantic",
                "/chat",
                "GET",
                Map.of(),
                null,
                null,
                Map.of(),
                "high risk refunds require manual approval",
                0.35D));

        assertThat(result.passed()).isTrue();
        server.verify();
    }

    @Test
    void failsWhenSemanticSimilarityIsBelowThreshold() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        EvalRunner runner = new EvalRunner(restTemplate, new EvalProperties());

        server.expect(once(), requestTo("http://edge.local/chat"))
                .andRespond(withSuccess("weather is sunny", MediaType.TEXT_PLAIN));

        var result = runner.execute("http://edge.local", new EvalCase(
                "semantic",
                "/chat",
                "GET",
                Map.of(),
                null,
                null,
                Map.of(),
                "high risk refunds require manual approval",
                0.5D));

        assertThat(result.passed()).isFalse();
        assertThat(result.error()).contains("semantic similarity below threshold");
        server.verify();
    }

    @Test
    void semanticSimilarityTokenizesCjkCharacters() {
        assertThat(EvalRunner.semanticSimilarity("高风险退款需要人工审批", "退款人工审批")).isGreaterThan(0.5D);
    }

    @Test
    void keepsLegacyResultConstructorOracleNeutral() {
        var result = new com.lrj.platform.protocol.eval.EvalCaseResult("case-1", true, 200, null, "ok", 1);

        assertThat(result.oracleMatched()).isTrue();
        assertThat(result.oracleExpected()).isNull();
    }

    @Test
    void passesWhenLlmJudgeScoreIsAboveThreshold() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        EvalRunner runner = new EvalRunner(restTemplate, new EvalProperties(),
                new StubJudge(true, 0.9D), new StubEmbedding(false, 0D));

        server.expect(once(), requestTo("http://edge.local/chat"))
                .andRespond(withSuccess("退款审批需要人工确认", MediaType.TEXT_PLAIN));

        var result = runner.execute("http://edge.local", judgeCase("退款流程说明", 0.7D));

        assertThat(result.passed()).isTrue();
        server.verify();
    }

    @Test
    void failsWhenLlmJudgeScoreIsBelowThreshold() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        EvalRunner runner = new EvalRunner(restTemplate, new EvalProperties(),
                new StubJudge(true, 0.4D), new StubEmbedding(false, 0D));

        server.expect(once(), requestTo("http://edge.local/chat"))
                .andRespond(withSuccess("今天天气不错", MediaType.TEXT_PLAIN));

        var result = runner.execute("http://edge.local", judgeCase("退款流程说明", 0.7D));

        assertThat(result.passed()).isFalse();
        assertThat(result.error()).contains("llm judge score below threshold");
        server.verify();
    }

    @Test
    void skipsLlmJudgeWhenNotConfigured() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        // judge 关闭：即便 case 带 judgeExpected 也不应触发断言。
        EvalRunner runner = new EvalRunner(restTemplate, new EvalProperties(),
                new StubJudge(false, 0.0D), new StubEmbedding(false, 0D));

        server.expect(once(), requestTo("http://edge.local/chat"))
                .andRespond(withSuccess("任意内容", MediaType.TEXT_PLAIN));

        var result = runner.execute("http://edge.local", judgeCase("退款流程说明", 0.7D));

        assertThat(result.passed()).isTrue();
        server.verify();
    }

    @Test
    void usesConfiguredDefaultJudgeThresholdWhenCaseOmitsIt() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        EvalProperties properties = new EvalProperties();
        properties.setJudgeMinScore(0.8D);
        EvalRunner runner = new EvalRunner(restTemplate, properties,
                new StubJudge(true, 0.6D), new StubEmbedding(false, 0D));

        server.expect(once(), requestTo("http://edge.local/chat"))
                .andRespond(withSuccess("部分相关", MediaType.TEXT_PLAIN));

        var result = runner.execute("http://edge.local", judgeCase("退款流程说明", null));

        assertThat(result.passed()).isFalse();
        assertThat(result.error()).contains("0.8");
        server.verify();
    }

    @Test
    void passesWhenEmbeddingSimilarityIsAboveThreshold() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        EvalRunner runner = new EvalRunner(restTemplate, new EvalProperties(),
                new StubJudge(false, 0D), new StubEmbedding(true, 0.92D));

        server.expect(once(), requestTo("http://edge.local/chat"))
                .andRespond(withSuccess("高风险退款需要人工审批", MediaType.TEXT_PLAIN));

        var result = runner.execute("http://edge.local", embeddingCase("退款需要人工审批", 0.75D));

        assertThat(result.passed()).isTrue();
        server.verify();
    }

    @Test
    void failsWhenEmbeddingSimilarityIsBelowThreshold() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        EvalRunner runner = new EvalRunner(restTemplate, new EvalProperties(),
                new StubJudge(false, 0D), new StubEmbedding(true, 0.3D));

        server.expect(once(), requestTo("http://edge.local/chat"))
                .andRespond(withSuccess("完全不相关的内容", MediaType.TEXT_PLAIN));

        var result = runner.execute("http://edge.local", embeddingCase("退款需要人工审批", 0.75D));

        assertThat(result.passed()).isFalse();
        assertThat(result.error()).contains("embedding similarity below threshold");
        server.verify();
    }

    @Test
    void skipsEmbeddingWhenNotConfigured() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        EvalRunner runner = new EvalRunner(restTemplate, new EvalProperties(),
                new StubJudge(false, 0D), new StubEmbedding(false, 0.0D));

        server.expect(once(), requestTo("http://edge.local/chat"))
                .andRespond(withSuccess("任意内容", MediaType.TEXT_PLAIN));

        var result = runner.execute("http://edge.local", embeddingCase("退款需要人工审批", 0.75D));

        assertThat(result.passed()).isTrue();
        server.verify();
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

    private static EvalCase judgeCase(String judgeExpected, Double judgeMinScore) {
        return new EvalCase(
                "judge",
                "/chat",
                "GET",
                Map.of(),
                null,
                null,
                Map.of(),
                null,
                null,
                judgeExpected,
                judgeMinScore,
                null,
                null);
    }

    private static EvalCase embeddingCase(String embeddingExpected, Double embeddingMinScore) {
        return new EvalCase(
                "embedding",
                "/chat",
                "GET",
                Map.of(),
                null,
                null,
                Map.of(),
                null,
                null,
                null,
                null,
                embeddingExpected,
                embeddingMinScore);
    }

    private record StubJudge(boolean enabled, double score) implements EvalJudge {
        @Override
        public double score(String criteria, String actualResponse) {
            return score;
        }
    }

    private record StubEmbedding(boolean enabled, double similarity) implements EvalEmbeddingComparator {
        @Override
        public double similarity(String expected, String actual) {
            return similarity;
        }
    }
}
