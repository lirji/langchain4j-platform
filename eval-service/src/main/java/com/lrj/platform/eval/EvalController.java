package com.lrj.platform.eval;

import com.lrj.platform.protocol.eval.EvalCase;
import com.lrj.platform.protocol.eval.EvalCaseResult;
import com.lrj.platform.protocol.eval.EvalDualRunReply;
import com.lrj.platform.protocol.eval.EvalDualRunRequest;
import com.lrj.platform.protocol.eval.EvalRunReply;
import com.lrj.platform.protocol.eval.EvalRunRequest;
import com.lrj.platform.protocol.eval.EvalSuiteRunRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class EvalController {

    private final EvalRunner evalRunner;
    private final EvalSuiteLoader suiteLoader;
    private final EvalReportWriter reportWriter;
    private final EvalProperties properties;
    private final EvalDualRunner dualRunner;

    public EvalController(EvalRunner evalRunner,
                          EvalSuiteLoader suiteLoader,
                          EvalReportWriter reportWriter,
                          EvalProperties properties,
                          EvalDualRunner dualRunner) {
        this.evalRunner = evalRunner;
        this.suiteLoader = suiteLoader;
        this.reportWriter = reportWriter;
        this.properties = properties;
        this.dualRunner = dualRunner;
    }

    @GetMapping("/eval/capabilities")
    public Map<String, Object> capabilities() {
        return Map.of(
                "service", "eval-service",
                "mode", "external-regression-client",
                "status", "http-runner-with-oracle",
                "assertions", List.of("expectedContains", "oracleContains", "expectedJsonPaths",
                        "semanticExpected", "judgeExpected", "embeddingExpected"),
                "dualRun", Map.of(
                        "modes", List.of("snapshot", "live"),
                        "metrics", List.of("passRate", "averageScore", "agreement"),
                        "gateStatus", "200 pass / 422 regression"),
                "baselineSuites", "classpath:eval/baselines/*.json or app.eval.baseline-directory",
                "oracleSnapshots", "classpath:eval/snapshots/*.json or app.eval.snapshot-directory");
    }

    @PostMapping("/eval/run")
    public ResponseEntity<?> run(@RequestBody EvalRunRequest request) {
        if (request == null || request.cases().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "cases are required"));
        }
        String targetBaseUrl = resolveTargetBaseUrl(request.targetBaseUrl());
        return ResponseEntity.accepted().body(runCases(targetBaseUrl, null, request.cases()));
    }

    @PostMapping("/eval/suites/{suiteName}/run")
    public ResponseEntity<?> runSuite(@PathVariable String suiteName,
                                      @RequestBody(required = false) EvalSuiteRunRequest request) {
        try {
            var suite = suiteLoader.load(suiteName);
            if (suite.cases().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "suite has no cases"));
            }
            String requestedBaseUrl = request == null ? null : request.targetBaseUrl();
            return ResponseEntity.accepted().body(runCases(resolveTargetBaseUrl(requestedBaseUrl), suite.name(), suite.cases()));
        } catch (EvalSuiteLoader.SuiteNotFoundException ex) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * 双跑（oracle vs candidate）。返回门禁明细，HTTP 恒 200（信息化）。CI fail 请用 {@link #gate}。
     */
    @PostMapping("/eval/dual-run")
    public ResponseEntity<?> dualRun(@RequestBody EvalDualRunRequest request) {
        try {
            return ResponseEntity.ok(dualRunner.run(request));
        } catch (EvalSuiteLoader.SuiteNotFoundException | EvalSnapshotLoader.SnapshotNotFoundException ex) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * CI 门禁：跑双跑 → 有回归返回 <strong>HTTP 422</strong> 供门禁 fail，body 是 {@link EvalDualRunReply}
     * （含 regressions 明细 + candidate/oracle 聚合 + agreement）；无回归返回 200。
     */
    @PostMapping("/eval/gate")
    public ResponseEntity<?> gate(@RequestBody EvalDualRunRequest request) {
        EvalDualRunReply reply;
        try {
            reply = dualRunner.run(request);
        } catch (EvalSuiteLoader.SuiteNotFoundException | EvalSnapshotLoader.SnapshotNotFoundException ex) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
        return reply.gate().passed()
                ? ResponseEntity.ok(reply)
                : ResponseEntity.unprocessableEntity().body(reply);
    }

    private EvalRunReply runCases(String targetBaseUrl, String suiteName, List<EvalCase> cases) {
        String runId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();
        List<EvalCaseResult> results = cases.stream()
                .map(evalCase -> evalRunner.execute(targetBaseUrl, evalCase))
                .toList();
        Instant finishedAt = Instant.now();
        long passed = results.stream().filter(EvalCaseResult::passed).count();
        EvalRunReply report = new EvalRunReply(
                results.size(),
                (int) passed,
                results.isEmpty() ? 0.0 : (double) passed / results.size(),
                results,
                runId,
                suiteName,
                targetBaseUrl,
                startedAt,
                Math.max(0L, finishedAt.toEpochMilli() - startedAt.toEpochMilli()),
                null,
                finishedAt);
        return reportWriter.write(report)
                .map(reportPath -> withReportPath(report, reportPath))
                .orElse(report);
    }

    private EvalRunReply withReportPath(EvalRunReply report, String reportPath) {
        return new EvalRunReply(
                report.total(),
                report.passed(),
                report.passRate(),
                report.results(),
                report.runId(),
                report.suiteName(),
                report.targetBaseUrl(),
                report.startedAt(),
                report.durationMs(),
                reportPath,
                report.finishedAt());
    }

    private String resolveTargetBaseUrl(String requestedBaseUrl) {
        return requestedBaseUrl == null || requestedBaseUrl.isBlank()
                ? properties.getDefaultTargetBaseUrl()
                : requestedBaseUrl;
    }
}
