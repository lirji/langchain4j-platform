package com.lrj.platform.knowledge.ingest.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
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

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RowMapper<IngestionJob> rowMapper = (rs, rowNum) -> {
        Map<String, String> rawSinks = read(rs.getString("SINKS_JSON"), STRING_MAP);
        EnumMap<IngestionSink, IngestionSinkState> sinks = new EnumMap<>(IngestionSink.class);
        rawSinks.forEach((sink, state) ->
                sinks.put(IngestionSink.valueOf(sink), IngestionSinkState.valueOf(state)));
        Set<IngestionSink> required = read(rs.getString("REQUIRED_SINKS_JSON"), STRING_SET)
                .stream()
                .map(IngestionSink::valueOf)
                .collect(Collectors.toUnmodifiableSet());
        return new IngestionJob(
                rs.getString("JOB_ID"),
                rs.getString("IDEMPOTENCY_KEY"),
                rs.getString("TENANT_ID"),
                rs.getString("USER_ID"),
                read(rs.getString("SCOPES_JSON"), STRING_SET),
                rs.getString("DEPARTMENT"),
                rs.getString("TRACE_ID"),
                rs.getString("DOCUMENT_ID"),
                rs.getString("DISPLAY_NAME"),
                rs.getString("CATEGORY"),
                rs.getLong("DOCUMENT_VERSION"),
                rs.getBoolean("NEW_DOCUMENT"),
                rs.getLong("REVISION"),
                new DocumentSourceRef(
                        rs.getString("SOURCE_BUCKET"),
                        rs.getString("SOURCE_OBJECT_KEY"),
                        rs.getString("SOURCE_HASH"),
                        rs.getString("SOURCE_CONTENT_TYPE"),
                        rs.getLong("SOURCE_SIZE")),
                IngestionStatus.valueOf(rs.getString("STATUS")),
                sinks,
                required,
                rs.getString("ERROR_TEXT"),
                rs.getTimestamp("CREATED_AT").toInstant(),
                rs.getTimestamp("UPDATED_AT").toInstant());
    };

    public JdbcIngestionJobStore(DataSource dataSource, ObjectMapper mapper) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.mapper = mapper;
        initialize();
    }

    @Override
    public IngestionJob createOrGet(IngestionJob job) {
        try {
            jdbc.update("""
                    INSERT INTO KNOWLEDGE_INGESTION_JOB (
                      TENANT_ID, JOB_ID, IDEMPOTENCY_KEY, USER_ID, SCOPES_JSON,
                      DEPARTMENT, TRACE_ID, DOCUMENT_ID, DISPLAY_NAME, CATEGORY,
                      DOCUMENT_VERSION, NEW_DOCUMENT, REVISION, SOURCE_BUCKET, SOURCE_OBJECT_KEY,
                      SOURCE_HASH, SOURCE_CONTENT_TYPE, SOURCE_SIZE, STATUS, SINKS_JSON,
                      REQUIRED_SINKS_JSON, ERROR_TEXT, CREATED_AT, UPDATED_AT
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    job.tenantId(), job.jobId(), job.idempotencyKey(), job.userId(),
                    write(job.scopes()), job.department(), job.traceId(), job.documentId(),
                    job.displayName(), job.category(), job.documentVersion(), job.newDocument(),
                    job.revision(),
                    job.source().bucket(), job.source().objectKey(), job.source().contentHash(),
                    job.source().contentType(), job.source().size(), job.status().name(),
                    writeSinks(job.sinks()), write(job.requiredSinks().stream()
                            .map(Enum::name).collect(Collectors.toUnmodifiableSet())),
                    job.error(), Timestamp.from(job.createdAt()), Timestamp.from(job.updatedAt()));
            return job;
        } catch (DuplicateKeyException ex) {
            return findByIdempotency(job.tenantId(), job.idempotencyKey())
                    .orElseThrow(() -> new IngestionJobConflictException("jobId already exists"));
        }
    }

    @Override
    public Optional<IngestionJob> find(String tenantId, String jobId) {
        return jdbc.query("""
                        SELECT * FROM KNOWLEDGE_INGESTION_JOB
                        WHERE TENANT_ID=? AND JOB_ID=?
                        """, rowMapper, tenantId, jobId)
                .stream().findFirst();
    }

    @Override
    public IngestionJob save(IngestionJob job, long expectedRevision) {
        if (job.revision() != expectedRevision) {
            throw new IngestionJobConflictException("stale ingestion job revision");
        }
        long nextRevision = expectedRevision + 1;
        int updated = jdbc.update("""
                        UPDATE KNOWLEDGE_INGESTION_JOB
                        SET REVISION=?, STATUS=?, SINKS_JSON=?, REQUIRED_SINKS_JSON=?,
                            ERROR_TEXT=?, UPDATED_AT=?
                        WHERE TENANT_ID=? AND JOB_ID=? AND REVISION=?
                        """,
                nextRevision, job.status().name(), writeSinks(job.sinks()),
                write(job.requiredSinks().stream()
                        .map(Enum::name).collect(Collectors.toUnmodifiableSet())),
                job.error(), Timestamp.from(job.updatedAt()), job.tenantId(), job.jobId(),
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
        return jdbc.query("""
                        SELECT * FROM KNOWLEDGE_INGESTION_JOB
                        WHERE STATUS IN ('RECEIVED','PROCESSING')
                        ORDER BY UPDATED_AT
                        LIMIT ?
                        """,
                rowMapper, limit);
    }

    @Override
    public List<IngestionJob> findRecoverable(Instant processingStaleBefore, int limit) {
        if (limit < 1) {
            return List.of();
        }
        return jdbc.query("""
                        SELECT * FROM KNOWLEDGE_INGESTION_JOB
                        WHERE STATUS IN ('PARTIAL','FAILED')
                           OR (STATUS='PROCESSING' AND UPDATED_AT < ?)
                        ORDER BY UPDATED_AT
                        LIMIT ?
                        """,
                rowMapper, Timestamp.from(processingStaleBefore), limit);
    }

    @Override
    public Optional<IngestionJob> findByIdempotency(String tenantId, String idempotencyKey) {
        return jdbc.query("""
                        SELECT * FROM KNOWLEDGE_INGESTION_JOB
                        WHERE TENANT_ID=? AND IDEMPOTENCY_KEY=?
                        """, rowMapper, tenantId, idempotencyKey)
                .stream().findFirst();
    }

    private void initialize() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS KNOWLEDGE_INGESTION_JOB (
                  TENANT_ID VARCHAR(128) NOT NULL,
                  JOB_ID VARCHAR(128) NOT NULL,
                  IDEMPOTENCY_KEY VARCHAR(512) NOT NULL,
                  USER_ID VARCHAR(256) NOT NULL,
                  SCOPES_JSON TEXT NOT NULL,
                  DEPARTMENT VARCHAR(256),
                  TRACE_ID VARCHAR(256) NOT NULL,
                  DOCUMENT_ID VARCHAR(256) NOT NULL,
                  DISPLAY_NAME VARCHAR(1024) NOT NULL,
                  CATEGORY VARCHAR(256),
                  DOCUMENT_VERSION BIGINT NOT NULL,
                  NEW_DOCUMENT BOOLEAN NOT NULL,
                  REVISION BIGINT NOT NULL,
                  SOURCE_BUCKET VARCHAR(256) NOT NULL,
                  SOURCE_OBJECT_KEY VARCHAR(1024) NOT NULL,
                  SOURCE_HASH VARCHAR(256) NOT NULL,
                  SOURCE_CONTENT_TYPE VARCHAR(256) NOT NULL,
                  SOURCE_SIZE BIGINT NOT NULL,
                  STATUS VARCHAR(32) NOT NULL,
                  SINKS_JSON TEXT NOT NULL,
                  REQUIRED_SINKS_JSON TEXT NOT NULL,
                  ERROR_TEXT TEXT,
                  CREATED_AT TIMESTAMP NOT NULL,
                  UPDATED_AT TIMESTAMP NOT NULL,
                  PRIMARY KEY (TENANT_ID, JOB_ID),
                  CONSTRAINT UK_KNOWLEDGE_INGEST_IDEM UNIQUE (TENANT_ID, IDEMPOTENCY_KEY),
                  CONSTRAINT UK_KNOWLEDGE_INGEST_VERSION
                    UNIQUE (TENANT_ID, DOCUMENT_ID, DOCUMENT_VERSION)
                )
                """);
        migrateLegacyTable();
    }

    private void migrateLegacyTable() {
        addColumnIfMissing("DISPLAY_NAME", "VARCHAR(1024)");
        addColumnIfMissing("CATEGORY", "VARCHAR(256)");
        addColumnIfMissing("NEW_DOCUMENT", "BOOLEAN DEFAULT FALSE");
        jdbc.update("""
                UPDATE KNOWLEDGE_INGESTION_JOB
                SET DISPLAY_NAME=DOCUMENT_ID
                WHERE DISPLAY_NAME IS NULL OR DISPLAY_NAME=''
                """);
        addUniqueIndexIfMissing(
                "UK_KNOWLEDGE_INGEST_VERSION",
                "TENANT_ID, DOCUMENT_ID, DOCUMENT_VERSION");
    }

    private void addColumnIfMissing(String column, String definition) {
        if (!columnExists(column)) {
            jdbc.execute("ALTER TABLE KNOWLEDGE_INGESTION_JOB ADD COLUMN "
                    + column + " " + definition);
        }
    }

    private boolean columnExists(String column) {
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null) {
            throw new IllegalStateException("ingestion job datasource is unavailable");
        }
        try (Connection connection = dataSource.getConnection()) {
            try (ResultSet columns = connection.getMetaData().getColumns(
                    connection.getCatalog(), null, "KNOWLEDGE_INGESTION_JOB", column)) {
                if (columns.next()) {
                    return true;
                }
            }
            try (ResultSet columns = connection.getMetaData().getColumns(
                    connection.getCatalog(), null,
                    "knowledge_ingestion_job", column.toLowerCase())) {
                return columns.next();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("cannot inspect ingestion job schema", ex);
        }
    }

    private void addUniqueIndexIfMissing(String indexName, String columns) {
        if (indexExists(indexName)) {
            return;
        }
        jdbc.execute("CREATE UNIQUE INDEX " + indexName
                + " ON KNOWLEDGE_INGESTION_JOB (" + columns + ")");
    }

    private boolean indexExists(String indexName) {
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null) {
            throw new IllegalStateException("ingestion job datasource is unavailable");
        }
        try (Connection connection = dataSource.getConnection();
             ResultSet indexes = connection.getMetaData().getIndexInfo(
                     connection.getCatalog(), null,
                     "KNOWLEDGE_INGESTION_JOB", true, false)) {
            while (indexes.next()) {
                if (indexName.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                    return true;
                }
            }
            return false;
        } catch (SQLException ex) {
            throw new IllegalStateException("cannot inspect ingestion job indexes", ex);
        }
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
