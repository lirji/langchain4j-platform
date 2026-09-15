package com.lrj.platform.knowledge.ingest.job;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** dev/test store；生产拆进程必须使用共享持久化实现。 */
public class InMemoryIngestionJobStore implements IngestionJobStore {

    private final Map<String, IngestionJob> jobs = new LinkedHashMap<>();
    private final Map<String, String> idempotencyIndex = new LinkedHashMap<>();

    @Override
    public synchronized IngestionJob createOrGet(IngestionJob job) {
        String idempotency = tenantKey(job.tenantId(), job.idempotencyKey());
        String existingId = idempotencyIndex.get(idempotency);
        if (existingId != null) {
            return jobs.get(tenantKey(job.tenantId(), existingId));
        }
        String key = tenantKey(job.tenantId(), job.jobId());
        if (jobs.containsKey(key)) {
            throw new IngestionJobConflictException("jobId already exists");
        }
        if (jobs.values().stream().anyMatch(existing -> existing.tenantId().equals(job.tenantId())
                && existing.documentId().equals(job.documentId())
                && existing.documentVersion() == job.documentVersion())) {
            throw new IngestionJobConflictException("document version already exists");
        }
        jobs.put(key, job);
        idempotencyIndex.put(idempotency, job.jobId());
        return job;
    }

    @Override
    public synchronized Optional<IngestionJob> find(String tenantId, String jobId) {
        return Optional.ofNullable(jobs.get(tenantKey(tenantId, jobId)));
    }

    @Override
    public synchronized Optional<IngestionJob> findByIdempotency(
            String tenantId,
            String idempotencyKey
    ) {
        String jobId = idempotencyIndex.get(tenantKey(tenantId, idempotencyKey));
        return jobId == null ? Optional.empty() : find(tenantId, jobId);
    }

    @Override
    public synchronized IngestionJob save(IngestionJob job, long expectedRevision) {
        String key = tenantKey(job.tenantId(), job.jobId());
        IngestionJob current = jobs.get(key);
        if (current == null) {
            throw new IngestionJobConflictException("job does not exist");
        }
        if (current.revision() != expectedRevision || job.revision() != expectedRevision) {
            throw new IngestionJobConflictException("stale ingestion job revision");
        }
        IngestionJob saved = job.withRevision(expectedRevision + 1);
        jobs.put(key, saved);
        return saved;
    }

    @Override
    public synchronized List<IngestionJob> findRunnable(int limit) {
        if (limit < 1) {
            return List.of();
        }
        return jobs.values().stream()
                .filter(job -> job.status() == IngestionStatus.RECEIVED
                        || job.status() == IngestionStatus.PROCESSING)
                .filter(job -> job.execution().leaseOwner() == null)
                .sorted(Comparator.comparing(IngestionJob::updatedAt))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized List<IngestionJob> findRecoverable(
            Instant processingStaleBefore,
            int limit
    ) {
        return findRecoverable(processingStaleBefore, Instant.now(), limit);
    }

    @Override
    public synchronized List<IngestionJob> findRecoverable(Instant processingStaleBefore, Instant now, int limit) {
        if (limit < 1) {
            return List.of();
        }
        return jobs.values().stream()
                .filter(job -> ((job.status() == IngestionStatus.PARTIAL || job.status() == IngestionStatus.FAILED)
                        && (job.execution().nextRetryAt() == null || !job.execution().nextRetryAt().isAfter(now)))
                        || (job.status() == IngestionStatus.PROCESSING
                        && (job.execution().leaseUntil() != null ? !job.execution().leaseUntil().isAfter(now)
                        : job.updatedAt().isBefore(processingStaleBefore))))
                .sorted(Comparator.comparing(IngestionJob::updatedAt))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized Map<IngestionStatus, Long> countsByStatus() {
        return jobs.values().stream().collect(java.util.stream.Collectors.groupingBy(
                IngestionJob::status, java.util.stream.Collectors.counting()));
    }

    @Override
    public synchronized Optional<Instant> oldestPending() {
        return jobs.values().stream().filter(j -> j.status() == IngestionStatus.RECEIVED
                || j.status() == IngestionStatus.PROCESSING || j.status() == IngestionStatus.PARTIAL
                || j.status() == IngestionStatus.FAILED).map(IngestionJob::createdAt).min(Instant::compareTo);
    }

    private static String tenantKey(String tenantId, String value) {
        if (tenantId == null || tenantId.isBlank() || value == null || value.isBlank()) {
            throw new IllegalArgumentException("tenantId and key are required");
        }
        return tenantId + '\u0000' + value;
    }
}
