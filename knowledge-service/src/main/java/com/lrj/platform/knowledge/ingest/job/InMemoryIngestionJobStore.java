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
                .sorted(Comparator.comparing(IngestionJob::updatedAt))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized List<IngestionJob> findRecoverable(
            Instant processingStaleBefore,
            int limit
    ) {
        if (limit < 1) {
            return List.of();
        }
        return jobs.values().stream()
                .filter(job -> job.status() == IngestionStatus.PARTIAL
                        || job.status() == IngestionStatus.FAILED
                        || (job.status() == IngestionStatus.PROCESSING
                        && job.updatedAt().isBefore(processingStaleBefore)))
                .sorted(Comparator.comparing(IngestionJob::updatedAt))
                .limit(limit)
                .toList();
    }

    private static String tenantKey(String tenantId, String value) {
        if (tenantId == null || tenantId.isBlank() || value == null || value.isBlank()) {
            throw new IllegalArgumentException("tenantId and key are required");
        }
        return tenantId + '\u0000' + value;
    }
}
