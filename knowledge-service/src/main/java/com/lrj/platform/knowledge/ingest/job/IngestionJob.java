package com.lrj.platform.knowledge.ingest.job;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 可持久化、可重放的文档入库状态。该对象只保存 S3 引用与逐 sink 结果，不保存框架状态或原文字节。
 */
public record IngestionJob(
        String jobId,
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
        boolean newDocument,
        long revision,
        DocumentSourceRef source,
        IngestionStatus status,
        Map<IngestionSink, IngestionSinkState> sinks,
        Set<IngestionSink> requiredSinks,
        String error,
        Instant createdAt,
        Instant updatedAt
) {

    public IngestionJob {
        jobId = requireText(jobId, "jobId");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        tenantId = requireText(tenantId, "tenantId");
        userId = requireText(userId, "userId");
        scopes = Set.copyOf(Objects.requireNonNull(scopes, "scopes is required"));
        department = normalizeOptional(department);
        traceId = requireText(traceId, "traceId");
        documentId = requireText(documentId, "documentId");
        displayName = requireText(displayName, "displayName");
        category = normalizeOptional(category);
        if (documentVersion < 1) {
            throw new IllegalArgumentException("documentVersion must be positive");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        Objects.requireNonNull(source, "source is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
        sinks = immutableSinkMap(sinks);
        requiredSinks = Set.copyOf(requiredSinks);
        if (requiredSinks.isEmpty() || !sinks.keySet().containsAll(requiredSinks)) {
            throw new IllegalArgumentException("requiredSinks must be present in sinks");
        }
    }

    public static IngestionJob received(
            String jobId,
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
            boolean newDocument,
            DocumentSourceRef source,
            Set<IngestionSink> enabledSinks,
            Instant now
    ) {
        EnumMap<IngestionSink, IngestionSinkState> states = new EnumMap<>(IngestionSink.class);
        enabledSinks.forEach(sink -> states.put(sink, IngestionSinkState.PENDING));
        Set<IngestionSink> required = enabledSinks.stream()
                .filter(IngestionSink::requiredByDefault)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new IngestionJob(jobId, idempotencyKey, tenantId, userId,
                scopes, department, traceId, documentId,
                displayName, category, documentVersion, newDocument,
                0, source, IngestionStatus.RECEIVED, states, required,
                null, now, now);
    }

    public IngestionJob withRevision(long nextRevision) {
        return new IngestionJob(jobId, idempotencyKey, tenantId, userId,
                scopes, department, traceId, documentId,
                displayName, category, documentVersion, newDocument,
                nextRevision, source, status, sinks, requiredSinks,
                error, createdAt, updatedAt);
    }

    private static Map<IngestionSink, IngestionSinkState> immutableSinkMap(
            Map<IngestionSink, IngestionSinkState> source
    ) {
        Objects.requireNonNull(source, "sinks is required");
        return Collections.unmodifiableMap(new EnumMap<>(source));
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " is required");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
