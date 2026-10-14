package com.lrj.platform.knowledge.operations;

import java.util.List;
import java.util.UUID;

/** 语言中立的运营快照；不保存模型、SDK或数据库连接对象。 */
public final class KnowledgeOperations {
    private KnowledgeOperations() {}
    public enum Reason { NO_ANSWER, INCORRECT, HELPFUL }
    public enum Status { DRAFT, EVALUATED, APPROVED, PUBLISHING, PUBLISH_FAILED, PUBLISHED }
    public record FeedbackInput(String idempotencyKey, String question, Reason reason, String comment, String documentId) {
        public FeedbackInput {
            required(idempotencyKey, 128, "idempotencyKey"); required(question, 2000, "question");
            if (reason == null) throw new IllegalArgumentException("reason required");
            optional(comment, 4000, "comment"); optional(documentId, 256, "documentId");
        }
    }
    public record Feedback(String id, String tenantId, String userId, FeedbackInput input, long createdAt) {}
    public record Check(String question, String requiredText) {
        public Check { required(question, 500, "check.question"); required(requiredText, 500, "check.requiredText"); }
    }
    public record Draft(String title, String content, String category, List<Check> checks) {
        public Draft {
            required(title, 200, "title"); required(content, 50000, "content"); optional(category, 128, "category");
            if (checks == null || checks.isEmpty() || checks.size() > 20 || checks.stream().anyMatch(java.util.Objects::isNull))
                throw new IllegalArgumentException("1..20 checks required");
            checks = List.copyOf(checks);
        }
    }
    public record CandidateInput(String idempotencyKey, String feedbackId, Draft draft) {
        public CandidateInput { required(idempotencyKey, 128, "idempotencyKey"); if (draft == null) throw new IllegalArgumentException("draft required"); }
    }
    public record CheckResult(String question, boolean passed, String reason) {}
    public record Evidence(String kind, String contentHash, int total, int passed, long checkedAt, List<CheckResult> results) {
        public Evidence { results = List.copyOf(results); }
        public boolean successful() { return total > 0 && total == passed; }
    }
    public record Release(long documentVersion, String reviewer, String publisher, String jobId) {}
    public record Candidate(String id, String tenantId, String feedbackId, String rollbackOf, String documentId,
                            Draft draft, Status status, long version, Evidence evaluation, Release release, Evidence probe,
                            String createdBy, String updatedBy, long createdAt, long updatedAt) {
        /** 新候选永远从DRAFT开始；客户端无法自行带入通过的评测或发布状态。 */
        public static Candidate create(String tenant, String actor, String feedback, String rollback,
                                       String document, Draft draft, long now) {
            return new Candidate(newId(now), tenant, feedback, rollback, document, draft, Status.DRAFT,
                    0, null, null, null, actor, actor, now, now);
        }
        /** 每次变化使用新不可变快照；存储层仍必须检查实际版本条件。 */
        public Candidate change(Draft nextDraft, Status nextStatus, Evidence nextEvaluation, Release nextRelease,
                                Evidence nextProbe, String actor, long now) {
            return new Candidate(id, tenantId, feedbackId, rollbackOf, documentId, nextDraft, nextStatus,
                    version + 1, nextEvaluation, nextRelease, nextProbe, createdBy, actor, createdAt, now);
        }
    }
    public record Audit(long id, String candidateId, String actor, String action, long version, long occurredAt) {}
    /** 时间前缀加随机标识支持稳定倒序游标，不把毫秒时间单独当唯一键。 */
    public static String newId(long now) { return String.format(java.util.Locale.ROOT, "%013d-%s", now, UUID.randomUUID()); }
    public static void required(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException(name + " is required and must not exceed " + max);
    }
    public static void optional(String value, int max, String name) {
        if (value != null && value.length() > max) throw new IllegalArgumentException(name + " must not exceed " + max);
    }
}
