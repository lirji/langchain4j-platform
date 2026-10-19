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

    private static final TypeReference<IngestionPreparation> PREPARATION = new TypeReference<>() { };
    private final IngestionJobStoreMapper db;
    private final ObjectMapper mapper;
    private final org.springframework.transaction.support.TransactionTemplate admission;
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
                        rs.leaseOwner, instant(rs.leaseUntil)),
                rs.preparationJson == null ? null : read(rs.preparationJson, PREPARATION));
    };

    public JdbcIngestionJobStore(DataSource dataSource, ObjectMapper mapper) {
        this(dataSource, mapper, 5);
    }

    /** 业务配置控制 SQL 与领取事务超时，不能使用0关闭执行上限。 */
    public JdbcIngestionJobStore(DataSource dataSource, ObjectMapper mapper, int queryTimeoutSeconds) {
        if (queryTimeoutSeconds < 1) throw new IngestionResourceException(IngestionResourceError.ADMISSION_CONFIG);
        this.db = PlusMapperFactory.create(dataSource, IngestionJobStoreMapper.class, queryTimeoutSeconds);
        this.mapper = mapper;
        this.admission = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        // 领取是短事务；每次统计读取最新已提交状态，外部模型与索引 I/O 不在锁内。
        admission.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        admission.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        admission.setTimeout(queryTimeoutSeconds);
        initialize();
    }

    @Override
    public IngestionJob createOrGet(IngestionJob job) {
        return createWithContentGuard(job, null).orElseThrow();
    }

    @Override
    public Optional<IngestionJob> createIfContentUnchanged(IngestionJob job, long generation) {
        return createWithContentGuard(job, generation);
    }

    /** 在同一短事务内锁守卫、检查序号、插入任务并推进序号；未知结果由幂等键恢复。 */
    private Optional<IngestionJob> createWithContentGuard(IngestionJob job, Long expectedGeneration) {
        return admission.execute(transaction -> {
            String hash = job.source().contentHash();
            db.ensureContentGuard(job.tenantId(), hash);
            long generation = db.lockContentGuard(job.tenantId(), hash);
            var replay = findByIdempotency(job.tenantId(), job.idempotencyKey());
            if (replay.isPresent()) return replay;
            if (expectedGeneration != null && generation != expectedGeneration) return Optional.empty();
            var result = insertOrReplay(job);
            if (result.jobId().equals(job.jobId()) && db.advanceContentGeneration(job.tenantId(), hash) != 1)
                throw new IngestionJobConflictException("content guard missing");
            return Optional.of(result);
        });
    }

    @Override
    public ContentSnapshot contentSnapshot(String tenantId, String contentHash, int limit) {
        if (limit < 1 || limit == Integer.MAX_VALUE) throw new IllegalArgumentException("invalid content candidate limit");
        // 顺序不可反转：读候选之后再读序号会遗漏两次查询之间创建的并发任务。
        Long generation = db.contentGeneration(tenantId, contentHash);
        var matches = Rows.map(db.contentMatches(tenantId, contentHash, limit + 1), rowMapper);
        return new ContentSnapshot(generation == null ? 0L : generation,
                matches.subList(0, Math.min(limit, matches.size())), matches.size() > limit);
    }

    private IngestionJob insertOrReplay(IngestionJob job) {
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
                    timestamp(job.execution().leaseUntil()),
                    job.preparation() == null ? null : write(job.preparation()));
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
                expectedRevision,
                job.preparation() == null ? null : write(job.preparation()),
                write(IngestionPreparation.PENDING));
        if (updated != 1) {
            throw new IngestionJobConflictException("stale ingestion job revision");
        }
        return job.withRevision(nextRevision);
    }

    /** 全局守卫序列化额度检查/租约写入；CAS 冲突使整个事务回滚，不消耗公平轮次。 */
    @Override
    public Optional<Claim> claim(ClaimRequest request) {
        return admission.execute(status -> {
            var guard = db.lockAdmission();
            if (guard == null) throw new IngestionResourceException(IngestionResourceError.ADMISSION_CONFIG);
            if (guard.globalLimit == null && guard.tenantLimit == null) {
                db.bindAdmissionPolicy(request.globalLimit(),request.tenantLimit());
            } else if (!java.util.Objects.equals(guard.globalLimit,request.globalLimit())
                    || !java.util.Objects.equals(guard.tenantLimit,request.tenantLimit())) {
                throw new IngestionResourceException(IngestionResourceError.ADMISSION_CONFIG);
            }
            if (db.countActive(Timestamp.from(request.now())) >= request.globalLimit()) return Optional.empty();
            var candidates = db.nextFair(request.tenantId(),request.jobId(),Timestamp.from(request.now()),request.tenantLimit());
            if (candidates.isEmpty()) return Optional.empty();
            var original = rowMapper.apply(candidates.getFirst(),0);
            var started = original.status() == IngestionStatus.RECEIVED
                    ? IngestionJobStateMachine.start(original,request.now()) : original;
            var leased = save(started.withExecution(started.execution().lease(java.util.UUID.randomUUID().toString(),
                    request.now().plus(request.leaseDuration()))), original.revision());
            long sequence = Math.incrementExact(guard.grantSequence);
            if (db.advanceAdmission(sequence) != 1) throw new IngestionJobConflictException("admission guard missing");
            if (db.updateTenantTurn(original.tenantId(),sequence) == 0) db.insertTenantTurn(original.tenantId(),sequence);
            return Optional.of(new Claim(original,leased));
        });
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
        // 校验 V12 已运行；启动读取不创建业务守卫行。
        db.contentGeneration("", "");
        // 启动时检测 V10 缺失，避免服务健康而领取轮询持续失败；自动提交查询立即释放锁。
        if (db.lockAdmission() == null) throw new IngestionResourceException(IngestionResourceError.ADMISSION_CONFIG);
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
