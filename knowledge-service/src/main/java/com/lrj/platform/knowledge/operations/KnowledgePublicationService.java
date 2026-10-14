package com.lrj.platform.knowledge.operations;

import com.lrj.platform.knowledge.ingest.job.*;
import com.lrj.platform.knowledge.lifecycle.DocumentRegistry;
import com.lrj.platform.security.TenantContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import static com.lrj.platform.knowledge.operations.KnowledgeOperations.*;

/** 持久化发布意图后调用现有入库服务；未知结果可用固定幂等键恢复，不把提交当成成功发布。 */
public final class KnowledgePublicationService {
    private final KnowledgeOperationsService operations;
    private final JdbcKnowledgeOperationsStore store;
    private final IngestionSubmissionService submissions;
    private final IngestionJobStore jobs;
    private final DocumentRegistry registry;
    private final Clock clock;
    public KnowledgePublicationService(KnowledgeOperationsService operations, JdbcKnowledgeOperationsStore store,
            IngestionSubmissionService submissions, IngestionJobStore jobs, DocumentRegistry registry, Clock clock) {
        this.operations = operations; this.store = store; this.submissions = submissions;
        this.jobs = jobs; this.registry = registry; this.clock = clock;
    }
    /** 审核必须引用当前草稿的通过证据；调用者不能手工传入passed=true。 */
    public Candidate approve(TenantContext.Tenant actor, String id, long version) {
        var current = current(actor, id, version);
        if (current.status() != Status.EVALUATED || !validEvidence(current)) throw OperationsException.conflict();
        return store.save(current.change(current.draft(), Status.APPROVED, current.evaluation(),
                new Release(0, actor.userId(), null, null), null, actor.userId(), clock.millis()), version, "APPROVE");
    }
    /** 先落意图与版本占位；模型/对象存储/索引均在本地事务之外调用。 */
    public Candidate publish(TenantContext.Tenant actor, String id, long version) {
        Candidate current = current(actor, id, version);
        if (current.status() == Status.PUBLISHED) return current;
        if (current.status() == Status.APPROVED) {
            if (!validEvidence(current)) throw OperationsException.conflict();
            long next = registry.get(actor.tenantId(), current.documentId()).map(info -> (long) info.version() + 1).orElse(1L);
            current = store.claim(current.change(current.draft(), Status.PUBLISHING, current.evaluation(),
                    new Release(next, current.release().reviewer(), actor.userId(), null), null, actor.userId(), clock.millis()), version);
        } else if (current.status() != Status.PUBLISHING && current.status() != Status.PUBLISH_FAILED) {
            throw OperationsException.conflict();
        }
        if (!actor.userId().equals(current.release().publisher()))
            throw new OperationsException("forbidden", "only the original publisher can resume this publication");
        try {
            IngestionJob job = submissions.submit(new IngestionSubmissionService.SubmitCommand(
                    jobKey(current), actor.tenantId(), actor.userId(), actor.scopes(), actor.department(), UUID.randomUUID().toString(),
                    current.documentId(), current.draft().title(), current.draft().category(), current.release().documentVersion(),
                    "text/plain", current.draft().content().getBytes(StandardCharsets.UTF_8)));
            validateJob(current, job);
            if (job.status() == IngestionStatus.FAILED || job.status() == IngestionStatus.PARTIAL || job.status() == IngestionStatus.MANUAL_REVIEW)
                job = submissions.retry(actor.tenantId(), actor.userId(), actor.scopes(), job.jobId());
            return bind(actor, current, job);
        } catch (IOException failure) {
            // PUBLISHING 已持久化，重试同一候选；不能换键重建任务，也不泄露对象存储异常。
            throw new OperationsException("unavailable", "publication not acknowledged; refresh and retry the same candidate");
        }
    }
    /** 读取job权威状态以恢复进程重启/响应丢失窗口，GET本身不推动发布。 */
    public Candidate sync(TenantContext.Tenant actor, String id, long version) {
        Candidate current = current(actor, id, version);
        if (current.release() == null || current.release().publisher() == null) throw OperationsException.conflict();
        var job = jobs.findByIdempotency(actor.tenantId(), jobKey(current));
        if (job.isEmpty()) return current;
        validateJob(current, job.get());
        return bind(actor, current, job.get());
    }
    /** 以旧内容建立新草稿，重新走评测和审核；不会倒退registry版本或绕过发布槽。 */
    public Candidate rollbackDraft(TenantContext.Tenant actor, String id, String key) {
        Candidate original = operations.get(actor, id);
        if (original.status() != Status.PUBLISHED) throw OperationsException.conflict();
        Candidate draft = Candidate.create(actor.tenantId(), actor.userId(), null, original.id(),
                original.documentId(), original.draft(), clock.millis());
        return store.create(draft, key, store.fingerprint(java.util.Map.of("rollbackOf", original.id())));
    }

    /** 检索前后都检查当前文档版本，防止评测途中有其他发布而产生错误的成功证据。 */
    public Candidate probe(TenantContext.Tenant actor, String id, long version, String token, KnowledgeProbe probe) {
        Candidate current = current(actor, id, version);
        if (current.status() != Status.PUBLISHED) throw OperationsException.conflict();
        if (!isCurrentVersion(current)) throw OperationsException.conflict();
        var results = probe.evaluate(actor, token, current);
        if (!isCurrentVersion(current)) throw OperationsException.conflict();
        if (results.size() != current.draft().checks().size()) throw new OperationsException("unavailable", "incomplete retrieval probe");
        Evidence evidence = new Evidence("RETRIEVAL_PROBE", store.fingerprint(current.draft()), results.size(),
                (int) results.stream().filter(CheckResult::passed).count(), clock.millis(), results);
        return store.save(current.change(current.draft(), current.status(), current.evaluation(), current.release(), evidence,
                actor.userId(), clock.millis()), version, "PROBE");
    }

    private boolean isCurrentVersion(Candidate candidate) {
        return registry.get(candidate.tenantId(), candidate.documentId())
                .map(info -> (long) info.version() == candidate.release().documentVersion()).orElse(false);
    }

    private Candidate bind(TenantContext.Tenant actor, Candidate current, IngestionJob job) {
        Status status = switch (job.status()) {
            case READY -> Status.PUBLISHED;
            case FAILED, PARTIAL, MANUAL_REVIEW, DELETED, DELETING -> Status.PUBLISH_FAILED;
            default -> Status.PUBLISHING;
        };
        var release = new Release(current.release().documentVersion(), current.release().reviewer(), current.release().publisher(), job.jobId());
        if (status == current.status() && release.equals(current.release())) return current;
        return store.save(current.change(current.draft(), status, current.evaluation(), release, current.probe(),
                actor.userId(), clock.millis()), current.version(), "SYNC");
    }
    private void validateJob(Candidate current, IngestionJob job) {
        // 即使租户内有人猜中发布幂等键，也不能把另一个用户/内容的READY任务当成本候选的发布证据。
        if (!job.userId().equals(current.release().publisher()) || !job.documentId().equals(current.documentId())
                || job.documentVersion() != current.release().documentVersion()
                || !job.displayName().equals(current.draft().title()) || !Objects.equals(job.category(), current.draft().category())
                || !job.source().contentHash().equals(contentHash(current.draft().content()))) throw OperationsException.conflict();
    }
    private boolean validEvidence(Candidate current) {
        return current.evaluation() != null && current.evaluation().successful()
                && store.fingerprint(current.draft()).equals(current.evaluation().contentHash());
    }
    private Candidate current(TenantContext.Tenant actor, String id, long expected) {
        Candidate result = operations.get(actor, id);
        if (result.version() != expected) throw OperationsException.conflict();
        return result;
    }
    private static String jobKey(Candidate candidate) { return "knowledge-ops-" + candidate.id(); }
    private static String contentHash(String content) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 required", e); }
    }
}
