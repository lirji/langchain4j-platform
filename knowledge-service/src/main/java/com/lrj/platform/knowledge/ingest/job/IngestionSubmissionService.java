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
        try {
            IngestionJob result = jobs.createOrGet(candidate);
            if (!result.jobId().equals(candidate.jobId())
                    && !result.source().equals(candidate.source())) {
                sources.delete(command.tenantId(), candidate.source());
            }
            return result;
        } catch (RuntimeException ex) {
            try {
                sources.delete(command.tenantId(), source);
            } catch (IOException cleanupFailure) {
                ex.addSuppressed(cleanupFailure);
            }
            throw ex;
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
            scopes = Set.copyOf(scopes);
            content = content == null ? null : content.clone();
        }

        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }
    }
}
