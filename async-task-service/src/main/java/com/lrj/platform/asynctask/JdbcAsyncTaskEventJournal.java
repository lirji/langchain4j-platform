package com.lrj.platform.asynctask;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * JDBC task event journal. Appends are serialized with a lock on the owning task row.
 */
@Component
@ConditionalOnProperty(name = "app.async-task.store", havingValue = "jdbc")
public class JdbcAsyncTaskEventJournal implements AsyncTaskEventJournal {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate txTemplate;
    private final AsyncTaskMetrics metrics;
    private Duration retention = Duration.ofHours(24);

    public JdbcAsyncTaskEventJournal(DataSource asyncTaskDataSource,
                                     ObjectMapper mapper,
                                     @Qualifier("asyncTaskTransactionManager")
                                     PlatformTransactionManager transactionManager) {
        this(asyncTaskDataSource, mapper, transactionManager, null);
    }

    @Autowired
    public JdbcAsyncTaskEventJournal(DataSource asyncTaskDataSource,
                                     ObjectMapper mapper,
                                     @Qualifier("asyncTaskTransactionManager")
                                     PlatformTransactionManager transactionManager,
                                     AsyncTaskMetrics metrics) {
        this.jdbc = new JdbcTemplate(asyncTaskDataSource);
        this.mapper = mapper;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.metrics = metrics;
        init();
    }

    private void init() {
        jdbc.queryForList("""
                SELECT TASK_ID, SEQUENCE, EVENT_KEY, EVENT_NAME, DATA_JSON, CREATED_AT, WORKER_ID
                FROM ASYNC_TASK_EVENT WHERE 1=0""");
    }

    @Override
    public AsyncTaskStreamEvent append(String taskId,
                                       String eventKey,
                                       String event,
                                       Object data,
                                       String workerId,
                                       Instant createdAt) {
        Instant persistedAt = Instant.ofEpochMilli(createdAt.toEpochMilli());
        boolean[] duplicateFound = new boolean[1];
        AsyncTaskStreamEvent result = txTemplate.execute(status -> {
            List<String> owners = jdbc.query(
                    "SELECT TASK_ID FROM ASYNC_TASK WHERE TASK_ID=? FOR UPDATE",
                    (rs, rowNum) -> rs.getString(1),
                    taskId);
            if (owners.isEmpty()) {
                throw new IllegalArgumentException("async task does not exist");
            }
            Optional<AsyncTaskStreamEvent> duplicate = findByEventKey(taskId, eventKey);
            if (duplicate.isPresent()) {
                duplicateFound[0] = true;
                return duplicate.get();
            }
            Long current = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(SEQUENCE), 0) FROM ASYNC_TASK_EVENT WHERE TASK_ID=?",
                    Long.class,
                    taskId);
            long sequence = (current == null ? 0 : current) + 1;
            jdbc.update("""
                    INSERT INTO ASYNC_TASK_EVENT
                    (TASK_ID, SEQUENCE, EVENT_KEY, EVENT_NAME, DATA_JSON, CREATED_AT, WORKER_ID)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""",
                    taskId,
                    sequence,
                    eventKey,
                    event,
                    json(data),
                    persistedAt.toEpochMilli(),
                    workerId);
            return new AsyncTaskStreamEvent(
                    taskId, sequence, eventKey, event, data, persistedAt, workerId);
        });
        if (result == null) {
            throw new IllegalStateException("event append transaction returned no result");
        }
        if (metrics != null) {
            metrics.eventAppended(event, duplicateFound[0]);
        }
        return result;
    }

    @Override
    public List<AsyncTaskStreamEvent> eventsAfter(String taskId, long sequence) {
        return jdbc.query("""
                SELECT * FROM ASYNC_TASK_EVENT
                WHERE TASK_ID=? AND SEQUENCE>?
                ORDER BY SEQUENCE""",
                this::mapEvent,
                taskId,
                sequence);
    }

    @Override
    public Optional<AsyncTaskStreamEvent> latest(String taskId) {
        return jdbc.query("""
                SELECT * FROM ASYNC_TASK_EVENT
                WHERE TASK_ID=?
                ORDER BY SEQUENCE DESC
                LIMIT 1""",
                this::mapEvent,
                taskId).stream().findFirst();
    }

    @Override
    public int cleanupBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM ASYNC_TASK_EVENT WHERE CREATED_AT < ?", cutoff.toEpochMilli());
    }

    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${app.async-task.cleanup-delay-ms:60000}",
            initialDelayString = "${app.async-task.cleanup-initial-delay-ms:60000}")
    public void cleanupExpired() {
        cleanupBefore(Instant.now().minus(retention));
    }

    @Value("${app.async-task.event.retention:PT24H}")
    void setRetention(Duration retention) {
        this.retention = retention;
    }

    private Optional<AsyncTaskStreamEvent> findByEventKey(String taskId, String eventKey) {
        return jdbc.query("""
                SELECT * FROM ASYNC_TASK_EVENT
                WHERE TASK_ID=? AND EVENT_KEY=?""",
                this::mapEvent,
                taskId,
                eventKey).stream().findFirst();
    }

    private AsyncTaskStreamEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new AsyncTaskStreamEvent(
                rs.getString("TASK_ID"),
                rs.getLong("SEQUENCE"),
                rs.getString("EVENT_KEY"),
                rs.getString("EVENT_NAME"),
                object(rs.getString("DATA_JSON")),
                Instant.ofEpochMilli(rs.getLong("CREATED_AT")),
                rs.getString("WORKER_ID"));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("failed to serialize async task event", ex);
        }
    }

    private Object object(String value) {
        try {
            return mapper.readValue(value, Object.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("failed to parse async task event", ex);
        }
    }
}
