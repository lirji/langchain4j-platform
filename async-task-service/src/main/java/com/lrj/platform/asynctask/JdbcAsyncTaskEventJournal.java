package com.lrj.platform.asynctask;

import com.lrj.platform.asynctask.mapper.AsyncTaskEventJournalMapper;
import com.lrj.platform.asynctask.mapper.AsyncTaskEventJournalRow;
import com.lrj.platform.persistence.PlusMapperFactory;
import com.lrj.platform.persistence.Rows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
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

    private final AsyncTaskEventJournalMapper db;
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
        this.db = PlusMapperFactory.create(asyncTaskDataSource, AsyncTaskEventJournalMapper.class);
        this.mapper = mapper;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.metrics = metrics;
        init();
    }

    private void init() {
        db.readInit1();
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
            List<String> owners = Rows.map(db.readAppend1(taskId), (rs, rowNum) -> rs.taskId);
            if (owners.isEmpty()) {
                throw new IllegalArgumentException("async task does not exist");
            }
            Optional<AsyncTaskStreamEvent> duplicate = findByEventKey(taskId, eventKey);
            if (duplicate.isPresent()) {
                duplicateFound[0] = true;
                return duplicate.get();
            }
            Long current = Rows.one(db.readAppend2(taskId));
            long sequence = (current == null ? 0 : current) + 1;
            db.writeAppend1(taskId, sequence, eventKey, event, json(data), persistedAt.toEpochMilli(), workerId);
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
        return Rows.map(db.readEventsAfter1(taskId, sequence), this::mapEvent);
    }

    @Override
    public Optional<AsyncTaskStreamEvent> latest(String taskId) {
        return Rows.map(db.readLatest1(taskId), this::mapEvent).stream().findFirst();
    }

    @Override
    public int cleanupBefore(Instant cutoff) {
        return db.writeCleanupBefore1(cutoff.toEpochMilli());
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
        return Rows.map(db.readFindByEventKey1(taskId, eventKey), this::mapEvent).stream().findFirst();
    }

    private AsyncTaskStreamEvent mapEvent(AsyncTaskEventJournalRow rs, int rowNum) {
        return new AsyncTaskStreamEvent(
                rs.taskId,
                Rows.longValue(rs.sequence),
                rs.eventKey,
                rs.eventName,
                object(rs.dataJson),
                Instant.ofEpochMilli(Rows.longValue(rs.createdAt)),
                rs.workerId);
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
