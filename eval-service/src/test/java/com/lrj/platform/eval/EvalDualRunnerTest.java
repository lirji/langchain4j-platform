package com.lrj.platform.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.eval.EvalCase;
import com.lrj.platform.protocol.eval.EvalCaseResult;
import com.lrj.platform.protocol.eval.EvalDualRunReply;
import com.lrj.platform.protocol.eval.EvalDualRunRequest;
import com.lrj.platform.protocol.eval.EvalSuiteDefinition;
import com.lrj.platform.protocol.eval.EvalTargetSummary;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link EvalDualRunner} 聚合/快照/一致性逻辑的确定性单测：用内存 stub {@link EvalRunner} 驱动，
 * 不真起单体或网关。
 */
class EvalDualRunnerTest {

    private static final EvalProperties PROPERTIES = new EvalProperties();

    // ---- runTarget 聚合 ----

    @Test
    void runTargetAggregatesTrialLevelPassRateAcrossRuns() {
        // 1 case × runs=3，结果 pass,pass,fail → passRate = 2/3。
        EvalDualRunner runner = new EvalDualRunner(
                new SequenceRunner(List.of(true, true, false), "eval-service"),
                null, null, PROPERTIES);
        EvalSuiteDefinition suite = suite(caseWithExpected("c1", "eval-service"));

        EvalTargetSummary summary = runner.runTarget("candidate", "http://c", suite, 3);

        assertThat(summary.totalCases()).isEqualTo(1);
        assertThat(summary.runs()).isEqualTo(3);
        assertThat(summary.passRate()).isCloseTo(2.0D / 3.0D, within(1e-9));
    }

    @Test
    void averageScoreUsesSemanticGradientWhenReferencePresent() {
        // 通过但响应与参考仅部分重合 → averageScore 应是梯度分（<1），区别于二值 passRate=1。
        EvalDualRunner runner = new EvalDualRunner(
                new FixedRunner(true, "high risk refunds require manual approval"),
                null, null, PROPERTIES);
        EvalSuiteDefinition suite = suite(caseWithExpected("c1", "refunds approval"));

        EvalTargetSummary summary = runner.runTarget("candidate", "http://c", suite, 1);

        assertThat(summary.passRate()).isEqualTo(1.0D);
        assertThat(summary.averageScore()).isGreaterThan(0.0D).isLessThan(1.0D);
    }

    @Test
    void averageScoreFallsBackToBinaryWhenNoReference() {
        EvalDualRunner runner = new EvalDualRunner(
                new FixedRunner(true, "anything"), null, null, PROPERTIES);
        EvalSuiteDefinition suite = suite(caseNoReference("c1"));

        EvalTargetSummary summary = runner.runTarget("candidate", "http://c", suite, 1);

        assertThat(summary.averageScore()).isEqualTo(1.0D);
    }

    // ---- live 双跑 ----

    @Test
    void liveModeRunsBothTargetsAndPassesWhenAligned() {
        EvalDualRunner runner = new EvalDualRunner(
                new FixedRunner(true, "eval-service capabilities"),
                new StubSuiteLoader(), null, PROPERTIES);

        EvalDualRunReply reply = runner.run(new EvalDualRunRequest(
                "smoke", "http://candidate", "http://oracle", null, null, null, null, null));

        assertThat(reply.mode()).isEqualTo("live");
        assertThat(reply.gate().passed()).isTrue();
        assertThat(reply.gate().agreement()).isEqualTo(1.0D); // 两侧响应一致
        assertThat(reply.gate().candidate().passRate()).isEqualTo(1.0D);
        assertThat(reply.gate().oracle().passRate()).isEqualTo(1.0D);
    }

    @Test
    void liveModeRequiresOracleBaseUrlWhenNoSnapshot() {
        EvalDualRunner runner = new EvalDualRunner(
                new FixedRunner(true, "x"), new StubSuiteLoader(), null, PROPERTIES);

        assertThat(catchException(() -> runner.run(new EvalDualRunRequest(
                "smoke", "http://candidate", null, null, null, null, null, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oracleBaseUrl");
    }

    // ---- snapshot 双跑（PR 轻量路径） ----

    @Test
    void snapshotModeLoadsFrozenOracleAndComparesCandidate() {
        // 用真实 classpath 快照 eval/snapshots/platform-smoke.json（oracle passRate=1, snippet 含 eval-service）。
        EvalSnapshotLoader snapshotLoader = new EvalSnapshotLoader(new ObjectMapper().findAndRegisterModules(), PROPERTIES);
        EvalDualRunner runner = new EvalDualRunner(
                new FixedRunner(true, "{\"service\":\"eval-service\",\"mode\":\"external-regression-client\"}"),
                new PlatformSmokeSuiteLoader(), snapshotLoader, PROPERTIES);

        EvalDualRunReply reply = runner.run(new EvalDualRunRequest(
                "platform-smoke", "http://candidate", null, "platform-smoke", null, null, null, null));

        assertThat(reply.mode()).isEqualTo("snapshot");
        assertThat(reply.gate().oracle().baseUrl()).isEqualTo("frozen-monolith-snapshot");
        assertThat(reply.gate().agreement()).isGreaterThan(0.6D);
        assertThat(reply.gate().passed()).isTrue();
    }

    @Test
    void rejectsBlankSuiteName() {
        EvalDualRunner runner = new EvalDualRunner(
                new FixedRunner(true, "x"), new StubSuiteLoader(), null, PROPERTIES);

        assertThat(catchException(() -> runner.run(new EvalDualRunRequest(
                "  ", "http://candidate", "http://oracle", null, null, null, null, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("suiteName");
    }

    // ---- helpers ----

    private static Throwable catchException(Runnable runnable) {
        try {
            runnable.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static EvalSuiteDefinition suite(EvalCase... cases) {
        return new EvalSuiteDefinition("smoke", List.of(cases));
    }

    private static EvalCase caseWithExpected(String id, String expected) {
        return new EvalCase(id, "/x", "GET", Map.of(), expected);
    }

    private static EvalCase caseNoReference(String id) {
        return new EvalCase(id, "/x", "GET", Map.of(), null);
    }

    /** 每次 execute 循环取下一个通过/失败结果，snippet 固定。 */
    private static class SequenceRunner extends EvalRunner {
        private final List<Boolean> outcomes;
        private final String snippet;
        private final AtomicInteger cursor = new AtomicInteger();

        SequenceRunner(List<Boolean> outcomes, String snippet) {
            super(new RestTemplate(), PROPERTIES);
            this.outcomes = outcomes;
            this.snippet = snippet;
        }

        @Override
        public EvalCaseResult execute(String targetBaseUrl, EvalCase evalCase) {
            boolean pass = outcomes.get(cursor.getAndIncrement() % outcomes.size());
            return new EvalCaseResult(evalCase.id(), pass, pass ? 200 : 500, pass ? null : "err", snippet, 1);
        }
    }

    /** 固定通过/失败 + 固定 snippet。 */
    private static class FixedRunner extends EvalRunner {
        private final boolean pass;
        private final String snippet;

        FixedRunner(boolean pass, String snippet) {
            super(new RestTemplate(), PROPERTIES);
            this.pass = pass;
            this.snippet = snippet;
        }

        @Override
        public EvalCaseResult execute(String targetBaseUrl, EvalCase evalCase) {
            return new EvalCaseResult(evalCase.id(), pass, pass ? 200 : 500, pass ? null : "err", snippet, 1);
        }
    }

    private static class StubSuiteLoader extends EvalSuiteLoader {
        StubSuiteLoader() {
            super(new ObjectMapper(), PROPERTIES);
        }

        @Override
        public EvalSuiteDefinition load(String suiteName) {
            return new EvalSuiteDefinition(suiteName, List.of(caseWithExpected("c1", "eval-service")));
        }
    }

    private static class PlatformSmokeSuiteLoader extends EvalSuiteLoader {
        PlatformSmokeSuiteLoader() {
            super(new ObjectMapper(), PROPERTIES);
        }

        @Override
        public EvalSuiteDefinition load(String suiteName) {
            return new EvalSuiteDefinition(suiteName, List.of(caseWithExpected("eval-capabilities", "eval-service")));
        }
    }
}
