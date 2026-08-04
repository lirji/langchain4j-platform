package com.lrj.platform.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

/**
 * {@link WorkflowIdempotencyStore} 的 JDBC 实现。
 *
 * <p>复合主键是并发正确性的最终裁决，不依赖进程锁或先查后建。MySQL 会让竞争 INSERT 等待首个事务
 * 提交；首个事务回滚时竞争者可获得 claim，提交时竞争者读到已经绑定的实例。JdbcTemplate 与
 * Flowable 使用同一个 {@code workflowDataSource}，因此由调用方的 {@code workflowTransactionManager}
 * 事务统一提交/回滚。
 */
@Component
@ConditionalOnProperty(name = "app.workflow.enabled", havingValue = "true")
public class JdbcWorkflowIdempotencyStore implements WorkflowIdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcWorkflowIdempotencyStore.class);

    private final JdbcTemplate jdbc;

    public JdbcWorkflowIdempotencyStore(DataSource workflowDataSource) {
        this.jdbc = new JdbcTemplate(workflowDataSource);
        init();
    }

    private void init() {
        jdbc.queryForList("""
                SELECT TENANT_ID, OPERATION_NAME, IDEMPOTENCY_KEY_HASH, REQUEST_HASH,
                       BUSINESS_KEY, INSTANCE_ID, CREATED_AT, UPDATED_AT
                FROM WF_IDEMPOTENCY WHERE 1=0""");
        log.info("WF_IDEMPOTENCY schema verified");
    }

    @Override
    public Claim claim(String tenantId,
                       String operation,
                       String keyHash,
                       String requestHash,
                       String businessKey) {
        long now = System.currentTimeMillis();
        try {
            jdbc.update("""
                            INSERT INTO WF_IDEMPOTENCY
                              (TENANT_ID, OPERATION_NAME, IDEMPOTENCY_KEY_HASH, REQUEST_HASH,
                               BUSINESS_KEY, INSTANCE_ID, CREATED_AT, UPDATED_AT)
                            VALUES (?, ?, ?, ?, ?, NULL, ?, ?)""",
                    tenantId, operation, keyHash, requestHash, businessKey, now, now);
            return Claim.acquiredClaim();
        } catch (DuplicateKeyException duplicate) {
            List<Row> rows = jdbc.query("""
                            SELECT REQUEST_HASH, INSTANCE_ID
                              FROM WF_IDEMPOTENCY
                             WHERE TENANT_ID = ? AND OPERATION_NAME = ? AND IDEMPOTENCY_KEY_HASH = ?""",
                    (rs, rowNum) -> new Row(rs.getString("REQUEST_HASH"), rs.getString("INSTANCE_ID")),
                    tenantId, operation, keyHash);
            if (rows.isEmpty()) {
                throw new IdempotencyConflictException("idempotency claim disappeared during conflict resolution");
            }
            Row row = rows.get(0);
            if (!requestHash.equals(row.requestHash())) {
                throw new IdempotencyConflictException("idempotency key is already bound to another request");
            }
            if (row.instanceId() == null || row.instanceId().isBlank()) {
                // 正常并发不会到这里：唯一键冲突会等首事务提交，而 attach 与 INSERT 同事务。
                // 此分支只可能是人工写入/旧故障遗留，必须 fail closed，不能再起第二个流程。
                throw new IdempotencyConflictException("idempotency claim has no committed workflow instance");
            }
            return Claim.replay(row.instanceId());
        }
    }

    @Override
    public void attachInstance(String tenantId,
                               String operation,
                               String keyHash,
                               String requestHash,
                               String instanceId) {
        int updated = jdbc.update("""
                        UPDATE WF_IDEMPOTENCY
                           SET INSTANCE_ID = ?, UPDATED_AT = ?
                         WHERE TENANT_ID = ? AND OPERATION_NAME = ? AND IDEMPOTENCY_KEY_HASH = ?
                           AND REQUEST_HASH = ? AND INSTANCE_ID IS NULL""",
                instanceId, System.currentTimeMillis(), tenantId, operation, keyHash, requestHash);
        if (updated != 1) {
            throw new IdempotencyConflictException("idempotency claim could not be bound atomically");
        }
    }

    @Override
    public void deleteByInstance(String instanceId) {
        jdbc.update("DELETE FROM WF_IDEMPOTENCY WHERE INSTANCE_ID = ?", instanceId);
    }

    private record Row(String requestHash, String instanceId) {}
}
