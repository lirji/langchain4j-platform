package com.lrj.platform.knowledge.ingest.job;

import com.lrj.platform.knowledge.ingest.job.mapper.IngestionJobStoreMapper;
import com.lrj.platform.knowledge.ingest.job.mapper.IngestionJobStoreRow;
import com.lrj.platform.persistence.PlusMapperFactory;
import com.lrj.platform.persistence.Rows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JDBC ingestion job store。唯一键保证 `(tenant_id,idempotency_key)` 幂等，revision 条件更新防止
 * 多 worker/reconciler 丢失更新。
 */
public class JdbcIngestionJobStore implements IngestionJobStore {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final TypeReference<Set<String>> STRING_SET = new TypeReference<>() {
    };

    private final IngestionJobStoreMapper db;
    private final ObjectMapper mapper;
    private final java.util.function.BiFunction<IngestionJobStoreRow, Integer, IngestionJob> rowMapper = (rs, rowNum) -> {
        Map<String, String> rawSinks = read(rs.sinksJson, STRING_MAP);
        EnumMap<IngestionSink, IngestionSinkState> sinks = new EnumMap<>(IngestionSink.class);
        rawSinks.forEach((sink, state) ->
                sinks.put(IngestionSink.valueOf(sink), IngestionSinkState.valueOf(state)));
        Set<IngestionSink> required = read(rs.requiredSinksJson, STRING_SET)
                .stream()
                .map(IngestionSink::valueOf)
                .collect(Collectors.toUnmodifiableSet());
        return new IngestionJob(
                rs.jobId,
                rs.idempotencyKey,
                rs.tenantId,
                rs.userId,
                read(rs.scopesJson, STRING_SET),
                rs.department,
                rs.traceId,
                rs.documentId,
                rs.displayName,
                rs.category,
                Rows.longValue(rs.documentVersion),
                Boolean.TRUE.equals(rs.newDocument),
                Rows.longValue(rs.revision),
                new DocumentSourceRef(
                        rs.sourceBucket,
                        rs.sourceObjectKey,
                        rs.sourceHash,
                        rs.sourceContentType,
                        Rows.longValue(rs.sourceSize)),
                IngestionStatus.valueOf(rs.status),
                sinks,
                required,
                rs.errorText,
                rs.createdAt.toInstant(),
                rs.updatedAt.toInstant(),
                new IngestionExecution(Rows.intValue(rs.retryCount), instant(rs.nextRetryAt),
                        rs.errorCode, rs.failedStage,
                        rs.leaseOwner, instant(rs.leaseUntil)));
    };

    public JdbcIngestionJobStore(DataSource dataSource, ObjectMapper mapper) {
        this.db = PlusMapperFactory.create(dataSource, IngestionJobStoreMapper.class);
        this.mapper = mapper;
        initialize();
    }

    @Override
    public IngestionJob createOrGet(IngestionJob job) {
        try {
            db.writeCreateOrGet1(
                    job.tenantId(),
                    job.jobId(),
                    job.idempotencyKey(),
                    job.userId(),
                    write(job.scopes()),
                    job.department(),
                    job.traceId(),
                    job.documentId(),
                    job.displayName(),
                    job.category(),
                    job.documentVersion(),
                    job.newDocument(),
                    job.revision(),
                    job.source().bucket(),
                    job.source().objectKey(),
                    job.source().contentHash(),
                    job.source().contentType(),
                    job.source().size(),
                    job.status().name(),
                    writeSinks(job.sinks()),
                    write(job.requiredSinks().stream()
                            .map(Enum::name).collect(Collectors.toUnmodifiableSet())),
                    job.error(),
                    Timestamp.from(job.createdAt()),
                    Timestamp.from(job.updatedAt()),
                    job.execution().retries(),
                    timestamp(job.execution().nextRetryAt()),
                    job.execution().errorCode(),
                    job.execution().failedStage(),
                    job.execution().leaseOwner(),
                    timestamp(job.execution().leaseUntil()));
            return job;
        } catch (DuplicateKeyException ex) {
            return findByIdempotency(job.tenantId(), job.idempotencyKey())
                    .orElseThrow(() -> new IngestionJobConflictException("jobId already exists"));
        }
    }

    @Override
    public Optional<IngestionJob> find(String tenantId, String jobId) {
        return Rows.map(db.readFind1(tenantId, jobId), rowMapper)
                .stream().findFirst();
    }

    @Override
    public IngestionJob save(IngestionJob job, long expectedRevision) {
        if (job.revision() != expectedRevision) {
            throw new IngestionJobConflictException("stale ingestion job revision");
        }
        long nextRevision = expectedRevision + 1;
        int updated = db.writeSave1(
                nextRevision,
                job.status().name(),
                writeSinks(job.sinks()),
                write(job.requiredSinks().stream()
                        .map(Enum::name).collect(Collectors.toUnmodifiableSet())),
                job.error(),
                Timestamp.from(job.updatedAt()),
                job.execution().retries(),
                timestamp(job.execution().nextRetryAt()),
                job.execution().errorCode(),
                job.execution().failedStage(),
                job.execution().leaseOwner(),
                timestamp(job.execution().leaseUntil()),
                job.tenantId(),
                job.jobId(),
                expectedRevision);
        if (updated != 1) {
            throw new IngestionJobConflictException("stale ingestion job revision");
        }
        return job.withRevision(nextRevision);
    }

    @Override
    public List<IngestionJob> findRunnable(int limit) {
        if (limit < 1) {
            return List.of();
        }
        return Rows.map(db.readFindRunnable1(limit), rowMapper);
    }

    @Override
    public List<IngestionJob> findRecoverable(Instant processingStaleBefore, int limit) {
        return findRecoverable(processingStaleBefore, Instant.now(), limit);
    }

    @Override
    public List<IngestionJob> findRecoverable(Instant processingStaleBefore, Instant now, int limit) {
        if (limit < 1) {
            return List.of();
        }
        return Rows.map(db.readFindRecoverable1(Timestamp.from(now), Timestamp.from(now), Timestamp.from(processingStaleBefore), limit), rowMapper);
    }

    @Override
    public Optional<IngestionJob> findByIdempotency(String tenantId, String idempotencyKey) {
        return Rows.map(db.readFindByIdempotency1(tenantId, idempotencyKey), rowMapper)
                .stream().findFirst();
    }

    @Override
    public Map<IngestionStatus, Long> countsByStatus() {
        Map<IngestionStatus, Long> result = new EnumMap<>(IngestionStatus.class);
        db.countsByStatus().forEach(row -> result.put(IngestionStatus.valueOf(row.status), row.total));
        return result;
    }

    @Override
    public Optional<Instant> oldestPending() {
        Timestamp oldest = Rows.one(db.readOldestPending1());
        return Optional.ofNullable(oldest).map(Timestamp::toInstant);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private void initialize() {
        db.readInitialize1();
    }

    private String writeSinks(Map<IngestionSink, IngestionSinkState> sinks) {
        return write(sinks.entrySet().stream().collect(Collectors.toMap(
                entry -> entry.getKey().name(),
                entry -> entry.getValue().name())));
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot serialize ingestion job", ex);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot deserialize ingestion job", ex);
        }
    }
}
