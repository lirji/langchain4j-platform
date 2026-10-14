package com.lrj.platform.protocol.knowledge;

import java.util.List;
import java.util.Set;

/** 检索质量契约：只衡量文档检索，不把它解释为生成回答质量或模型成本。 */
public final class RetrievalQuality {
    private RetrievalQuality() {}

    /** visibility参与文档身份，避免共享库和租户库同名文档互相冒充相关结果。 */
    public record Target(String visibility, String docId) {
        public Target {
            if (!Set.of("tenant", "public").contains(visibility == null ? "" : visibility)) throw new IllegalArgumentException("visibility must be tenant or public");
            text(docId, 256, "docId");
        }
    }
    /** 标注由运营人员确认；关联反馈只提供来源，不自动产生正确答案。 */
    public record Case(String id, String question, List<Target> relevant, String feedbackId) {
        public Case {
            text(id, 64, "case.id"); text(question, 2000, "question");
            if (relevant == null || relevant.isEmpty() || relevant.size() > 20 || relevant.stream().anyMatch(java.util.Objects::isNull))
                throw new IllegalArgumentException("1..20 relevant documents required");
            relevant = List.copyOf(relevant);
            if (relevant.stream().distinct().count() != relevant.size()) throw new IllegalArgumentException("duplicate relevant document");
            if (feedbackId != null) text(feedbackId, 64, "feedbackId");
        }
    }
    /** 门槛随评测集固化；质量下降使用绝对差值，延迟使用毫秒增量，不伪造容量SLO。 */
    public record Settings(int topK, double minScore, String category, double allowedDrop, long allowedP95IncreaseMs) {
        public Settings {
            if (topK < 1 || topK > 20 || !Double.isFinite(minScore) || minScore < 0 || minScore > 1
                    || !Double.isFinite(allowedDrop) || allowedDrop < 0 || allowedDrop > 1
                    || allowedP95IncreaseMs < 0 || allowedP95IncreaseMs > 120000)
                throw new IllegalArgumentException("invalid retrieval settings or gate tolerance");
            if (category != null && category.length() > 128) throw new IllegalArgumentException("category too long");
        }
    }
    /** 小批同步运行最多20题；编辑后重新建立基线，历史运行保留原始定义。 */
    public record Definition(String name, List<Case> cases, Settings settings) {
        public Definition {
            text(name, 128, "name");
            if (cases == null || cases.isEmpty() || cases.size() > 20 || cases.stream().anyMatch(java.util.Objects::isNull) || settings == null)
                throw new IllegalArgumentException("1..20 cases and settings required");
            cases = List.copyOf(cases);
            if (cases.stream().map(Case::id).distinct().count() != cases.size()) throw new IllegalArgumentException("duplicate case id");
        }
    }
    public record Dataset(String id, long revision, Definition definition, String baselineRunId, String updatedBy, long updatedAt) {}
    public record Create(String idempotencyKey, Definition definition) {
        public Create { text(idempotencyKey, 128, "idempotencyKey"); if (definition == null) throw new IllegalArgumentException("definition required"); }
    }
    public record Edit(Long expectedRevision, Definition definition) {
        public Edit { revision(expectedRevision); if (definition == null) throw new IllegalArgumentException("definition required"); }
    }
    public record Execute(Long expectedRevision, String idempotencyKey, String label) {
        public Execute { revision(expectedRevision); text(idempotencyKey, 128, "idempotencyKey"); text(label, 128, "label"); }
    }
    public record Baseline(Long expectedRevision, String runId) {
        public Baseline { revision(expectedRevision); text(runId, 64, "runId"); }
    }
    public enum Status { RUNNING, SUCCEEDED, FAILED }
    public enum Verdict { BASELINE_REQUIRED, PASS, REGRESSION, FAILED }
    /** 仅保存定位信息，不复制检索到的正文或调用凭据。 */
    public record Observed(Target document, String version) {}
    public record CaseResult(String caseId, List<Observed> retrieved, double recall, double precision,
                             double mrr, boolean hit, long latencyMs) {
        public CaseResult { retrieved = List.copyOf(retrieved); }
    }
    public record Metrics(double recall, double precision, double mrr, double hitRate, long p50Ms, long p95Ms) {}
    public record Gate(Verdict verdict, List<String> reasons, Double recallDelta, Double precisionDelta,
                       Double mrrDelta, Double hitRateDelta, Long p95DeltaMs) {
        public Gate { reasons = List.copyOf(reasons); }
    }
    /** 快照、基线ID及权限摘要在运行开始时固定，终态禁止覆盖。 */
    public record Run(String id, String datasetId, String datasetHash, String accessHash, String actor,
                      Definition snapshot, long datasetRevision, String label, String baselineRunId,
                      Status status, long startedAt, long deadlineAt, Long finishedAt,
                      List<CaseResult> results, Metrics metrics, Gate gate, String failure) {
        public Run { results = List.copyOf(results); }
    }
    private static void text(String value, int limit, String name) {
        if (value == null || value.isBlank() || value.length() > limit) throw new IllegalArgumentException(name + " is required and too long values are rejected");
    }
    private static void revision(Long value) {
        if (value == null || value < 0) throw new IllegalArgumentException("non-negative expectedRevision required");
    }
}
