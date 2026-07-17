package com.lrj.platform.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.eval.retrieval.RetrievalEvaluator;
import com.lrj.platform.protocol.eval.EvalCase;
import com.lrj.platform.protocol.eval.EvalCaseResult;
import com.lrj.platform.protocol.eval.EvalDualRunReply;
import com.lrj.platform.protocol.eval.EvalDualRunRequest;
import com.lrj.platform.protocol.eval.EvalRunReply;
import com.lrj.platform.protocol.eval.EvalRunRequest;
import com.lrj.platform.protocol.eval.EvalSuiteDefinition;
import com.lrj.platform.protocol.eval.EvalSuiteRunRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EvalControllerTest：验证 {@link EvalController} 的接口契约——capabilities 暴露 oracle 断言清单、
 * {@code /eval/run} 与命名 suite 的 202 受理与空用例 400、以及门禁语义（candidate 匹配 oracle 返回 200、
 * 回归返回 422 且带 regressions、dualRun 恒 200、缺 suiteName 返回 400），用桩 runner/suiteLoader 隔离外部依赖。
 */
class EvalControllerTest {

    @Test
    void capabilitiesExposeOracleAssertions() {
        EvalController controller = controller(new PassingEvalRunner(), new FixedSuiteLoader(), new EvalProperties());

        var capabilities = controller.capabilities();

        assertThat(capabilities).containsEntry("status", "http-runner-with-oracle");
        assertThat(capabilities.get("assertions")).isEqualTo(List.of("expectedContains", "oracleContains",
                "expectedJsonPaths", "semanticExpected", "judgeExpected", "embeddingExpected"));
    }

    @Test
    void acceptsEvalRunForValidCases() {
        EvalProperties properties = new EvalProperties();
        properties.setDefaultTargetBaseUrl("http://edge");
        EvalController controller = controller(new PassingEvalRunner(), new FixedSuiteLoader(), properties);

        var response = controller.run(new EvalRunRequest(null, List.of(
                new EvalCase("chat-smoke", "/chat", "POST", Map.of("message", "hi"), null))));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        EvalRunReply body = (EvalRunReply) response.getBody();
        assertThat(body.total()).isEqualTo(1);
        assertThat(body.passed()).isEqualTo(1);
    }

    @Test
    void rejectsEmptyEvalRun() {
        EvalController controller = controller(new PassingEvalRunner(), new FixedSuiteLoader(), new EvalProperties());

        var response = controller.run(new EvalRunRequest("http://edge", List.of()));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void runsNamedSuite() {
        EvalProperties properties = new EvalProperties();
        properties.setDefaultTargetBaseUrl("http://default");
        PassingEvalRunner runner = new PassingEvalRunner();
        EvalController controller = controller(runner, new FixedSuiteLoader(), properties);

        var response = controller.runSuite("platform-smoke", new EvalSuiteRunRequest("http://edge"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        EvalRunReply body = (EvalRunReply) response.getBody();
        assertThat(body.total()).isEqualTo(1);
        assertThat(body.passed()).isEqualTo(1);
        assertThat(body.suiteName()).isEqualTo("platform-smoke");
        assertThat(body.targetBaseUrl()).isEqualTo("http://edge");
        assertThat(body.runId()).isNotBlank();
        assertThat(runner.lastTargetBaseUrl).isEqualTo("http://edge");
    }

    @Test
    void gateReturns200WhenCandidateMatchesLiveOracle() {
        EvalProperties properties = new EvalProperties();
        EvalController controller = controller(new PassingEvalRunner(), new FixedSuiteLoader(), properties);

        var response = controller.gate(new EvalDualRunRequest(
                "platform-smoke", "http://candidate", "http://oracle", null, null, null, null, null));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        EvalDualRunReply body = (EvalDualRunReply) response.getBody();
        assertThat(body.mode()).isEqualTo("live");
        assertThat(body.gate().passed()).isTrue();
    }

    @Test
    void gateReturns422WhenCandidateRegressesAgainstOracle() {
        EvalProperties properties = new EvalProperties();
        // oracle 全过、candidate 全挂 → passRate 跌破容差 + agreement 低 → 回归。
        EvalController controller = controller(
                new DivergentRunner("http://candidate"), new FixedSuiteLoader(), properties);

        var response = controller.gate(new EvalDualRunRequest(
                "platform-smoke", "http://candidate", "http://oracle", null, null, null, null, null));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        EvalDualRunReply body = (EvalDualRunReply) response.getBody();
        assertThat(body.gate().passed()).isFalse();
        assertThat(body.gate().regressions()).isNotEmpty();
    }

    @Test
    void dualRunAlwaysReturns200WithGateDetail() {
        EvalController controller = controller(
                new DivergentRunner("http://candidate"), new FixedSuiteLoader(), new EvalProperties());

        var response = controller.dualRun(new EvalDualRunRequest(
                "platform-smoke", "http://candidate", "http://oracle", null, null, null, null, null));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        EvalDualRunReply body = (EvalDualRunReply) response.getBody();
        assertThat(body.gate().passed()).isFalse();
    }

    @Test
    void gateRejectsMissingSuiteName() {
        EvalController controller = controller(new PassingEvalRunner(), new FixedSuiteLoader(), new EvalProperties());

        var response = controller.gate(new EvalDualRunRequest(
                null, "http://candidate", "http://oracle", null, null, null, null, null));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    private static EvalController controller(EvalRunner runner, EvalSuiteLoader loader, EvalProperties properties) {
        EvalSnapshotLoader snapshotLoader = new EvalSnapshotLoader(new ObjectMapper().findAndRegisterModules(), properties);
        EvalDualRunner dualRunner = new EvalDualRunner(runner, loader, snapshotLoader, properties);
        RetrievalEvaluator retrievalEvaluator = new RetrievalEvaluator(
                (base, question, topK, category) -> java.util.List.of());
        return new EvalController(runner, loader, disabledReportWriter(), properties, dualRunner, retrievalEvaluator);
    }

    private static EvalReportWriter disabledReportWriter() {
        return new EvalReportWriter(new ObjectMapper().findAndRegisterModules(), new EvalProperties());
    }

    private static class PassingEvalRunner extends EvalRunner {

        private String lastTargetBaseUrl;

        PassingEvalRunner() {
            super(new RestTemplate(), new EvalProperties());
        }

        @Override
        public com.lrj.platform.protocol.eval.EvalCaseResult execute(String targetBaseUrl, EvalCase evalCase) {
            this.lastTargetBaseUrl = targetBaseUrl;
            return new com.lrj.platform.protocol.eval.EvalCaseResult(evalCase.id(), true, 200, null, "ok", 1);
        }
    }

    /** oracle 全过（snippet "ok"），candidate 全挂（snippet 语义无关）→ 用于回归判定。 */
    private static class DivergentRunner extends EvalRunner {

        private final String candidateBaseUrl;

        DivergentRunner(String candidateBaseUrl) {
            super(new RestTemplate(), new EvalProperties());
            this.candidateBaseUrl = candidateBaseUrl;
        }

        @Override
        public EvalCaseResult execute(String targetBaseUrl, EvalCase evalCase) {
            if (candidateBaseUrl.equals(targetBaseUrl)) {
                return new EvalCaseResult(evalCase.id(), false, 500, "boom", "totally unrelated body", 1);
            }
            return new EvalCaseResult(evalCase.id(), true, 200, null, "ok", 1);
        }
    }

    private static class FixedSuiteLoader extends EvalSuiteLoader {

        FixedSuiteLoader() {
            super(new ObjectMapper(), new EvalProperties());
        }

        @Override
        public EvalSuiteDefinition load(String suiteName) {
            return new EvalSuiteDefinition(suiteName, List.of(
                    new EvalCase("capabilities", "/eval/capabilities", "GET", Map.of(), "eval-service")));
        }
    }
}
