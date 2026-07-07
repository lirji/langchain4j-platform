package com.lrj.platform.eval;

import com.lrj.platform.protocol.eval.EvalCase;
import com.lrj.platform.protocol.eval.EvalCaseResult;
import com.lrj.platform.protocol.eval.EvalDualRunReply;
import com.lrj.platform.protocol.eval.EvalDualRunRequest;
import com.lrj.platform.protocol.eval.EvalGateResult;
import com.lrj.platform.protocol.eval.EvalGateTolerances;
import com.lrj.platform.protocol.eval.EvalOracleSnapshot;
import com.lrj.platform.protocol.eval.EvalSuiteDefinition;
import com.lrj.platform.protocol.eval.EvalTargetSummary;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 双跑编排器：同一 suite（经 {@link EvalSuiteLoader} 单一来源）分别打 oracle（冻结单体）与 candidate
 * （edge-gateway），聚合各自 passRate / averageScore + 跨目标语义一致性 agreement，交给纯函数
 * {@link EvalGate} 判回归。
 *
 * <p>两种形态：
 * <ul>
 *   <li><b>snapshot（PR）</b>：请求带 {@code oracleSnapshot}，只实跑 candidate，oracle 侧读预存快照。快/稳。</li>
 *   <li><b>live（nightly）</b>：请求带 {@code oracleBaseUrl}，oracle/candidate 都实打（现场起单体做 oracle）。</li>
 * </ul>
 *
 * <p>非网络逻辑（聚合、打分、agreement）全确定性，单测用 stub {@link EvalRunner} 驱动即可，不需真起单体/网关。
 */
@Service
public class EvalDualRunner {

    private final EvalRunner evalRunner;
    private final EvalSuiteLoader suiteLoader;
    private final EvalSnapshotLoader snapshotLoader;
    private final EvalProperties properties;

    public EvalDualRunner(EvalRunner evalRunner,
                          EvalSuiteLoader suiteLoader,
                          EvalSnapshotLoader snapshotLoader,
                          EvalProperties properties) {
        this.evalRunner = evalRunner;
        this.suiteLoader = suiteLoader;
        this.snapshotLoader = snapshotLoader;
        this.properties = properties;
    }

    public EvalDualRunReply run(EvalDualRunRequest request) {
        if (request == null || request.suiteName() == null || request.suiteName().isBlank()) {
            throw new IllegalArgumentException("suiteName is required");
        }
        EvalSuiteDefinition suite = suiteLoader.load(request.suiteName());
        if (suite.cases().isEmpty()) {
            throw new IllegalArgumentException("suite has no cases");
        }

        EvalGateTolerances tolerances = resolveTolerances(request);
        int runs = tolerances.runs();

        String runId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();

        String candidateBaseUrl = resolveBaseUrl(request.candidateBaseUrl());
        EvalTargetSummary candidate = runTarget("candidate", candidateBaseUrl, suite, runs);

        String mode;
        EvalTargetSummary oracle;
        if (request.oracleSnapshot() != null && !request.oracleSnapshot().isBlank()) {
            mode = "snapshot";
            EvalOracleSnapshot snapshot = snapshotLoader.load(request.oracleSnapshot());
            oracle = snapshot.oracle();
            if (oracle == null) {
                throw new IllegalArgumentException("oracle snapshot has no oracle summary");
            }
        } else {
            if (request.oracleBaseUrl() == null || request.oracleBaseUrl().isBlank()) {
                throw new IllegalArgumentException("live mode requires oracleBaseUrl (or supply oracleSnapshot)");
            }
            mode = "live";
            oracle = runTarget("oracle", request.oracleBaseUrl(), suite, runs);
        }

        double agreement = agreement(suite, candidate, oracle);
        EvalGateResult gate = EvalGate.evaluate(candidate, oracle, agreement, tolerances);

        Instant finishedAt = Instant.now();
        long durationMs = Math.max(0L, finishedAt.toEpochMilli() - startedAt.toEpochMilli());
        return new EvalDualRunReply(runId, suite.name(), mode, gate, startedAt, durationMs, finishedAt);
    }

    /** 对一个目标把 suite 逐 case 打 {@code runs} 次并聚合。 */
    EvalTargetSummary runTarget(String name, String baseUrl, EvalSuiteDefinition suite, int runs) {
        List<EvalCase> cases = suite.cases();
        List<EvalCaseResult> representatives = new ArrayList<>(cases.size());
        int totalTrials = 0;
        int passedTrials = 0;
        double scoreSum = 0D;
        for (EvalCase evalCase : cases) {
            String reference = referenceText(evalCase);
            double caseScoreSum = 0D;
            EvalCaseResult first = null;
            for (int i = 0; i < runs; i++) {
                EvalCaseResult result = evalRunner.execute(baseUrl, evalCase);
                if (first == null) {
                    first = result;
                }
                totalTrials++;
                if (result.passed()) {
                    passedTrials++;
                }
                caseScoreSum += attemptScore(result, reference);
            }
            scoreSum += caseScoreSum / runs;
            if (first != null) {
                representatives.add(first);
            }
        }
        double passRate = totalTrials == 0 ? 0D : (double) passedTrials / totalTrials;
        double averageScore = cases.isEmpty() ? 0D : scoreSum / cases.size();
        return new EvalTargetSummary(name, baseUrl, cases.size(), runs, passRate, averageScore, representatives);
    }

    /**
     * 单次 attempt 的连续打分：有参考答案时取响应片段对参考的确定性语义相似度（0..1，质量梯度）；
     * 无参考答案时退化成二值通过分。
     */
    private static double attemptScore(EvalCaseResult result, String reference) {
        if (reference == null || reference.isBlank()) {
            return result.passed() ? 1D : 0D;
        }
        String snippet = result.responseSnippet();
        if (snippet == null || snippet.isBlank()) {
            return 0D;
        }
        return clamp01(EvalRunner.semanticSimilarity(snippet, reference));
    }

    private static double clamp01(double value) {
        return Math.max(0D, Math.min(1D, value));
    }

    /** 跨目标语义一致性：逐 case 比对 candidate / oracle 响应片段的语义相似度，取均值。 */
    private static double agreement(EvalSuiteDefinition suite,
                                    EvalTargetSummary candidate,
                                    EvalTargetSummary oracle) {
        Map<String, String> candidateSnippets = snippetsById(candidate);
        Map<String, String> oracleSnippets = snippetsById(oracle);
        int counted = 0;
        double sum = 0D;
        for (EvalCase evalCase : suite.cases()) {
            String id = evalCase.id();
            String candidateSnippet = candidateSnippets.get(id);
            String oracleSnippet = oracleSnippets.get(id);
            double sim = (isBlank(candidateSnippet) || isBlank(oracleSnippet))
                    ? 0D
                    : clamp01(EvalRunner.semanticSimilarity(candidateSnippet, oracleSnippet));
            sum += sim;
            counted++;
        }
        return counted == 0 ? 0D : sum / counted;
    }

    private static Map<String, String> snippetsById(EvalTargetSummary summary) {
        Map<String, String> map = new LinkedHashMap<>();
        for (EvalCaseResult result : summary.results()) {
            map.putIfAbsent(result.id(), result.responseSnippet());
        }
        return map;
    }

    private static String referenceText(EvalCase evalCase) {
        return firstNonBlank(
                evalCase.semanticExpected(),
                evalCase.judgeExpected(),
                evalCase.embeddingExpected(),
                evalCase.expectedContains(),
                evalCase.oracleContains());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private EvalGateTolerances resolveTolerances(EvalDualRunRequest request) {
        double passRateTolerance = request.passRateTolerance() == null
                ? properties.getGatePassRateTolerance()
                : request.passRateTolerance();
        double averageScoreTolerance = request.averageScoreTolerance() == null
                ? properties.getGateAverageScoreTolerance()
                : request.averageScoreTolerance();
        double minAgreement = request.minAgreement() == null
                ? properties.getGateMinAgreement()
                : request.minAgreement();
        int runs = request.runs() == null ? properties.getGateRuns() : request.runs();
        return new EvalGateTolerances(passRateTolerance, averageScoreTolerance, minAgreement, runs);
    }

    private String resolveBaseUrl(String requested) {
        return requested == null || requested.isBlank()
                ? properties.getDefaultTargetBaseUrl()
                : requested;
    }
}
