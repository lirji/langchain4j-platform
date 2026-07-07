package com.lrj.platform.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.eval.EvalCase;
import com.lrj.platform.protocol.eval.EvalRunReply;
import com.lrj.platform.protocol.eval.EvalRunRequest;
import com.lrj.platform.protocol.eval.EvalSuiteDefinition;
import com.lrj.platform.protocol.eval.EvalSuiteRunRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvalControllerTest {

    @Test
    void capabilitiesExposeOracleAssertions() {
        EvalController controller = new EvalController(new PassingEvalRunner(), new FixedSuiteLoader(), disabledReportWriter(), new EvalProperties());

        var capabilities = controller.capabilities();

        assertThat(capabilities).containsEntry("status", "http-runner-with-oracle");
        assertThat(capabilities.get("assertions")).isEqualTo(List.of("expectedContains", "oracleContains",
                "expectedJsonPaths", "semanticExpected", "judgeExpected", "embeddingExpected"));
    }

    @Test
    void acceptsEvalRunForValidCases() {
        EvalProperties properties = new EvalProperties();
        properties.setDefaultTargetBaseUrl("http://edge");
        EvalController controller = new EvalController(new PassingEvalRunner(), new FixedSuiteLoader(), disabledReportWriter(), properties);

        var response = controller.run(new EvalRunRequest(null, List.of(
                new EvalCase("chat-smoke", "/chat", "POST", Map.of("message", "hi"), null))));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        EvalRunReply body = (EvalRunReply) response.getBody();
        assertThat(body.total()).isEqualTo(1);
        assertThat(body.passed()).isEqualTo(1);
    }

    @Test
    void rejectsEmptyEvalRun() {
        EvalController controller = new EvalController(new PassingEvalRunner(), new FixedSuiteLoader(), disabledReportWriter(), new EvalProperties());

        var response = controller.run(new EvalRunRequest("http://edge", List.of()));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void runsNamedSuite() {
        EvalProperties properties = new EvalProperties();
        properties.setDefaultTargetBaseUrl("http://default");
        PassingEvalRunner runner = new PassingEvalRunner();
        EvalController controller = new EvalController(runner, new FixedSuiteLoader(), disabledReportWriter(), properties);

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
