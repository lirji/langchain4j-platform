package com.lrj.platform.knowledge.ingest.job;

import com.lrj.platform.knowledge.authz.AuthzMode;
import com.lrj.platform.knowledge.authz.KnowledgeAuthz;
import com.lrj.platform.knowledge.lifecycle.DocumentInfo;
import com.lrj.platform.knowledge.lifecycle.DocumentRegistry;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * ingest-api 的窄应用服务：先把原文落权威对象存储，再幂等创建 durable job。
 * 不执行解析、embedding 或索引副作用。
 */
public class IngestionSubmissionService {

    private final DocumentSourceStore sources;
    private final IngestionJobStore jobs;
    private final DocumentRegistry registry;
    private final KnowledgeAuthz authorization;
    private final Clock clock;
    private final Set<IngestionSink> enabledSinks;

    public IngestionSubmissionService(
            DocumentSourceStore sources,
            IngestionJobStore jobs,
            DocumentRegistry registry,
            KnowledgeAuthz authorization,
            Clock clock,
            Set<IngestionSink> enabledSinks
    ) {
        this.sources = Objects.requireNonNull(sources);
        this.jobs = Objects.requireNonNull(jobs);
        this.registry = Objects.requireNonNull(registry);
        this.authorization = Objects.requireNonNull(authorization);
        this.clock = Objects.requireNonNull(clock);
        this.enabledSinks = Set.copyOf(enabledSinks);
        if (this.enabledSinks.stream().noneMatch(IngestionSink::requiredByDefault)) {
            throw new IllegalArgumentException("at least one required sink must be enabled");
        }
    }

    public IngestionJob submit(SubmitCommand command) throws IOException {
        validate(command);
        var existing = jobs.findByIdempotency(command.tenantId(), command.idempotencyKey());
        if (existing.isPresent()) {
            validateReplay(command, existing.get());
            return existing.get();
        }

        DocumentInfo registered = registry
                .get(command.tenantId(), command.documentId())
                .orElse(null);
        boolean newDocument = registered == null;
        validateVersionAndAuthorization(command, registered);

        String contentHash = "sha256:" + sha256(command.content());
        DocumentSourceRef source = sources.put(new DocumentSourceStore.PutSource(
                command.tenantId(),
                command.documentId(),
                command.documentVersion(),
                command.contentType(),
                command.content().length,
                contentHash,
                new ByteArrayInputStream(command.content())));
        Instant now = clock.instant();
        IngestionJob candidate = IngestionJob.received(
                UUID.randomUUID().toString(),
                command.idempotencyKey(),
                command.tenantId(),
                command.userId(),
                command.scopes(),
                command.department(),
                command.traceId(),
                command.documentId(),
                command.displayName(),
                command.category(),
                command.documentVersion(),
                newDocument,
                source,
                enabledSinks,
                now);
        // 对象键可能被并发获胜任务复用；数据库失败/响应丢失时不得删除可能已被引用的原文。
        IngestionJob result = jobs.createOrGet(candidate);
        validateReplay(command, result);
        return result;
    }

    /** 重放也检查请求指纹与提交者；相同租户不能通过猜测幂等键读取他人任务。 */
    private void validateReplay(SubmitCommand command, IngestionJob existing) {
        requireOwner(existing, command.userId());
        if (!existing.documentId().equals(command.documentId())
                || existing.documentVersion() != command.documentVersion()
                || !existing.displayName().equals(command.displayName())
                || !Objects.equals(existing.category(), command.category())
                || !existing.source().contentType().equals(command.contentType())
                || !existing.source().contentHash().equals("sha256:" + sha256(command.content()))) {
            throw new IngestionJobConflictException("idempotency key was already used with a different request");
        }
    }

    /** 任务详情属于提交者；不存在与跨用户访问均返回 404，避免泄露任务存在性。 */
    public IngestionJob get(String tenantId, String userId, String jobId) {
        IngestionJob job = get(tenantId, jobId);
        requireOwner(job, userId);
        return job;
    }

    /** 提交者显式恢复已失败任务；先重新检查 ingest 与文档编辑权限，再用 revision 竞争恢复权。 */
    public IngestionJob retry(String tenantId, String userId, Set<String> scopes, String jobId) {
        if (!scopes.contains("ingest")) {
            throw new IngestionAuthorizationException("ingest scope required");
        }
        IngestionJob job = get(tenantId, userId, jobId);
        if (registry.get(tenantId, job.documentId()).isPresent()
                && !authorization.checkDocument(tenantId, userId, job.documentId(), "edit")) {
            throw new IngestionAuthorizationException("edit permission required");
        }
        if (job.status() != IngestionStatus.MANUAL_REVIEW && job.status() != IngestionStatus.PARTIAL
                && job.status() != IngestionStatus.FAILED) {
            throw new IngestionJobConflictException("only failed jobs can be retried");
        }
        return jobs.save(IngestionJobStateMachine.retry(job, clock.instant())
                .withExecution(IngestionExecution.EMPTY), job.revision());
    }

    private static void requireOwner(IngestionJob job, String userId) {
        if (!job.userId().equals(userId)) {
            throw new IngestionJobNotFoundException(job.jobId());
        }
    }

    public IngestionJob get(String tenantId, String jobId) {
        return jobs.find(tenantId, jobId)
                .orElseThrow(() -> new IngestionJobNotFoundException(jobId));
    }

    private static void validate(SubmitCommand command) {
        Objects.requireNonNull(command, "command");
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()
                || command.content() == null || command.content().length == 0) {
            throw new IllegalArgumentException("idempotencyKey and non-empty content are required");
        }
        if (command.tenantId() == null || command.userId() == null || command.documentId() == null
                || command.contentType() == null || !command.scopes().contains("ingest")) {
            throw new IllegalArgumentException("tenant, user, document, content type and ingest scope are required");
        }
        if (command.displayName() == null || command.displayName().isBlank()
                || command.documentVersion() < 1) {
            throw new IllegalArgumentException(
                    "displayName and positive documentVersion are required");
        }
    }

    private void validateVersionAndAuthorization(
            SubmitCommand command,
            DocumentInfo registered
    ) {
        if (registered == null) {
            if (command.documentVersion() != 1) {
                throw new IngestionJobConflictException(
                        "new document must start at version 1");
            }
            if (authorization.mode() == AuthzMode.ENFORCE
                    && (command.department() == null || command.department().isBlank())) {
                throw new IngestionAuthorizationException(
                        "uploader department is required for document creation");
            }
            return;
        }
        if (command.documentVersion() != registered.version() + 1L) {
            throw new IngestionJobConflictException(
                    "documentVersion must advance the registered version by one");
        }
        if (!authorization.checkDocument(
                command.tenantId(), command.userId(), command.documentId(), "edit")) {
            throw new IngestionAuthorizationException(
                    "edit permission is required to replace document");
        }
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public record SubmitCommand(
            String idempotencyKey,
            String tenantId,
            String userId,
            Set<String> scopes,
            String department,
            String traceId,
            String documentId,
            String displayName,
            String category,
            long documentVersion,
            String contentType,
            byte[] content
    ) {
        public SubmitCommand {
            idempotencyKey = normalized(idempotencyKey);
            tenantId = normalized(tenantId);
            userId = normalized(userId);
            documentId = normalized(documentId);
            displayName = normalized(displayName);
            category = normalized(category);
            department = normalized(department);
            contentType = normalized(contentType);
            traceId = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
            scopes = Set.copyOf(scopes);
            content = content == null ? null : content.clone();
        }

        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }
    }
}
