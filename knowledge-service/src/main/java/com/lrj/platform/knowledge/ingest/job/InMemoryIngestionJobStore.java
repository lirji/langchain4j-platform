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
    private final Map<String, Long> contentGenerations = new LinkedHashMap<>();

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
        contentGenerations.merge(tenantKey(job.tenantId(), job.source().contentHash()), 1L, Long::sum);
        return job;
    }

    /** 与 JDBC 相同的条件创建语义；仅用于单进程开发和测试。 */
    @Override
    public synchronized Optional<IngestionJob> createIfContentUnchanged(IngestionJob job, long generation) {
        var replay = findByIdempotency(job.tenantId(), job.idempotencyKey());
        if (replay.isPresent()) return replay;
        if (contentGenerations.getOrDefault(tenantKey(job.tenantId(), job.source().contentHash()), 0L) != generation)
            return Optional.empty();
        return Optional.of(createOrGet(job));
    }

    @Override
    public synchronized ContentSnapshot contentSnapshot(String tenantId, String contentHash, int limit) {
        if (limit < 1 || limit == Integer.MAX_VALUE) throw new IllegalArgumentException("invalid content candidate limit");
        var matches = jobs.values().stream().filter(job -> job.tenantId().equals(tenantId)
                && job.source().contentHash().equals(contentHash))
                .sorted(Comparator.comparing(IngestionJob::jobId)).limit((long) limit + 1).toList();
        return new ContentSnapshot(contentGenerations.getOrDefault(tenantKey(tenantId, contentHash), 0L),
                matches.subList(0, Math.min(limit, matches.size())), matches.size() > limit);
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
        if (current.preparation() != null && current.preparation().bound()
                && !current.preparation().equals(job.preparation())) {
            throw new IngestionJobConflictException("prepared result is immutable");
        }
        IngestionJob saved = job.withRevision(expectedRevision + 1);
        jobs.put(key, saved);
        return saved;
    }

    private final Map<String,Long> tenantTurns = new LinkedHashMap<>();
    private long grantSequence;
    private Integer globalLimit, tenantLimit;

    /** dev/test 的同一存储实例模拟事务；不提供跨进程保证。 */
    @Override
    public synchronized Optional<Claim> claim(ClaimRequest request) {
        if (globalLimit == null) { globalLimit = request.globalLimit(); tenantLimit = request.tenantLimit(); }
        if (globalLimit != request.globalLimit() || tenantLimit != request.tenantLimit())
            throw new IngestionResourceException(IngestionResourceError.ADMISSION_CONFIG);
        var active = jobs.values().stream().filter(job -> job.status() == IngestionStatus.PROCESSING
                && job.execution().leaseOwner() != null && (job.execution().leaseUntil() == null
                || job.execution().leaseUntil().isAfter(request.now()))).toList();
        if (active.size() >= globalLimit) return Optional.empty();
        var selected = jobs.values().stream().filter(job -> (job.status() == IngestionStatus.RECEIVED
                    || job.status() == IngestionStatus.PROCESSING) && job.execution().leaseOwner() == null)
                .filter(job -> request.jobId() == null || (job.tenantId().equals(request.tenantId()) && job.jobId().equals(request.jobId())))
                .filter(job -> active.stream().filter(a -> a.tenantId().equals(job.tenantId())).count() < tenantLimit)
                .min(Comparator.comparingLong((IngestionJob job) -> tenantTurns.getOrDefault(job.tenantId(),0L))
                        .thenComparing(IngestionJob::createdAt).thenComparing(IngestionJob::tenantId).thenComparing(IngestionJob::jobId));
        if (selected.isEmpty()) return Optional.empty();
        var original = selected.get();
        var started = original.status() == IngestionStatus.RECEIVED ? IngestionJobStateMachine.start(original,request.now()) : original;
        var leased = save(started.withExecution(started.execution().lease(java.util.UUID.randomUUID().toString(),
                request.now().plus(request.leaseDuration()))), original.revision());
        tenantTurns.put(original.tenantId(), ++grantSequence);
        return Optional.of(new Claim(original,leased));
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
