package com.lrj.platform.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcWorkflowIdempotencyStoreTest {

    private JdbcWorkflowIdempotencyStore store;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        DataSource dataSource = WorkflowTestDatabase.migrated("idempotency-" + System.nanoTime());
        store = new JdbcWorkflowIdempotencyStore(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void committedClaimReplaysBoundInstance() {
        transaction.executeWithoutResult(status -> {
            assertThat(store.claim("acme", "refund_start", "key", "request", "business").acquired()).isTrue();
            store.attachInstance("acme", "refund_start", "key", "request", "process-1");
        });

        WorkflowIdempotencyStore.Claim replay = transaction.execute(status ->
                store.claim("acme", "refund_start", "key", "request", "business"));

        assertThat(replay).isEqualTo(new WorkflowIdempotencyStore.Claim(false, "process-1"));
        assertThat(rowCount()).isOne();
    }

    @Test
    void sameKeyWithDifferentRequestConflicts() {
        transaction.executeWithoutResult(status -> {
            store.claim("acme", "refund_start", "key", "request-a", "business");
            store.attachInstance("acme", "refund_start", "key", "request-a", "process-1");
        });

        assertThatThrownBy(() -> transaction.execute(status ->
                store.claim("acme", "refund_start", "key", "request-b", "business")))
                .isInstanceOf(WorkflowIdempotencyStore.IdempotencyConflictException.class);
        assertThat(rowCount()).isOne();
    }

    @Test
    void tenantIsPartOfUniqueConstraint() {
        transaction.executeWithoutResult(status -> {
            store.claim("tenant-a", "refund_start", "key", "request", "business-a");
            store.attachInstance("tenant-a", "refund_start", "key", "request", "process-a");
            store.claim("tenant-b", "refund_start", "key", "request", "business-b");
            store.attachInstance("tenant-b", "refund_start", "key", "request", "process-b");
        });

        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    void failedTransactionDoesNotPoisonKey() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            store.claim("acme", "refund_start", "key", "request", "business");
            throw new IllegalStateException("simulated workflow failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(rowCount()).isZero();

        transaction.executeWithoutResult(status -> {
            assertThat(store.claim("acme", "refund_start", "key", "request", "business").acquired()).isTrue();
            store.attachInstance("acme", "refund_start", "key", "request", "process-retry");
        });
        assertThat(rowCount()).isOne();
    }

    @Test
    void deleteByInstanceRemovesCommittedBinding() {
        transaction.executeWithoutResult(status -> {
            store.claim("acme", "refund_start", "key", "request", "business");
            store.attachInstance("acme", "refund_start", "key", "request", "process-1");
        });

        transaction.executeWithoutResult(status -> store.deleteByInstance("process-1"));

        assertThat(rowCount()).isZero();
    }

    @Test
    void missingSchemaFailsWithoutCreatingTables() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:workflow_missing_schema;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");

        assertThatThrownBy(() -> new JdbcWorkflowIdempotencyStore(dataSource))
                .isInstanceOf(DataAccessException.class);
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='WF_IDEMPOTENCY'",
                Integer.class)).isZero();
    }

    private int rowCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM WF_IDEMPOTENCY", Integer.class);
        return count == null ? 0 : count;
    }
}
