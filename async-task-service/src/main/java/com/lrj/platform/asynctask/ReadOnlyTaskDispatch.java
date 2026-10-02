package com.lrj.platform.asynctask;

import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatus;
import com.lrj.platform.protocol.asynctask.ReadOnlyTaskClaimReply;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Java持有只读任务调度权威. 同库事务保存身份, 全局锁判定跨副本租户配额, 原leaseEpoch防旧执行器写入.
 * 调度锁只覆盖数据库操作, 不覆盖模型调用. 不接受客户端提供的身份或持久化凭据.
 */
@Component
@ConditionalOnProperty(name = "app.async-task.dispatch.enabled", havingValue = "true")
public final class ReadOnlyTaskDispatch {
    static final Set<String> KINDS = Set.of("agent.readonly.run.v1", "agent.readonly.dag.v1",
            "agent.readonly.dag-plan.v1", "agent.readonly.analyst.v1", "agent.readonly.process.v1");
    private static final int MAX_TOTAL_QUEUED = 10_000;
    private static final int MAX_TENANT_QUEUED = 100;
    private static final int MAX_RECOVERY_ATTEMPTS = 3;
    private static final int MAX_RECOVERY_AGE_SECONDS = 86_400;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JdbcAsyncTaskStore store;
    private final InternalToken tokens;
    private final int globalLimit;
    private final int tenantLimit;
    private final int leaseSeconds;

    public ReadOnlyTaskDispatch(DataSource dataSource,
            @Qualifier("asyncTaskTransactionManager") PlatformTransactionManager manager,
            JdbcAsyncTaskStore store, InternalToken tokens,
            @Value("${app.async-task.dispatch.global-limit:32}") int globalLimit,
            @Value("${app.async-task.dispatch.tenant-limit:2}") int tenantLimit,
            @Value("${app.async-task.dispatch.lease-seconds:60}") int leaseSeconds) {
        if (globalLimit < 1 || globalLimit > 256 || tenantLimit < 1 || tenantLimit > globalLimit
                || leaseSeconds < 15 || leaseSeconds > 120)
            throw new IllegalArgumentException("invalid read-only dispatch resource limits");
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(manager);
        this.store = store;
        this.tokens = tokens;
        this.globalLimit = globalLimit;
        this.tenantLimit = tenantLimit;
        this.leaseSeconds = leaseSeconds;
        jdbc.queryForList("SELECT TASK_ID, DEPARTMENT, TRACE_ID, ATTEMPTS FROM ASYNC_TASK_DISPATCH WHERE 1=0");
        // RS256纯验签节点必须启动失败, 不能领取后才发现没有工具凭据签发能力.
        tokens.mint(new TenantContext.Tenant("dispatch-self-check", "dispatch-self-check", Set.of("agent")));
    }

    /** 入队与可信上下文同事务提交, ACK前不会留下无上下文任务. */
    public void create(AsyncTask task, TenantContext.Tenant caller, String trace) {
        String traceId = trace == null || trace.isBlank() ? UUID.randomUUID().toString() : trace;
        if (traceId.length() > 128 || caller.department() != null && caller.department().length() > 256)
            throw new IllegalArgumentException("dispatch context exceeds bounds");
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT LOCK_ID FROM ASYNC_TASK_DISPATCH_LOCK WHERE LOCK_ID=1 FOR UPDATE", Integer.class);
            Long pending = jdbc.queryForObject("SELECT COUNT(*) FROM ASYNC_TASK t JOIN ASYNC_TASK_DISPATCH d ON t.TASK_ID=d.TASK_ID WHERE t.STATUS IN ('PENDING','RUNNING')", Long.class);
            Long tenantPending = jdbc.queryForObject("SELECT COUNT(*) FROM ASYNC_TASK t JOIN ASYNC_TASK_DISPATCH d ON t.TASK_ID=d.TASK_ID WHERE t.STATUS IN ('PENDING','RUNNING') AND t.TENANT_ID=?", Long.class, task.tenantId());
            if (pending != null && pending >= MAX_TOTAL_QUEUED || tenantPending != null && tenantPending >= MAX_TENANT_QUEUED)
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "durable task queue capacity exhausted");
            store.put(task);
            jdbc.update("INSERT INTO ASYNC_TASK_DISPATCH (TASK_ID, DEPARTMENT, TRACE_ID, CREATED_AT) VALUES (?, ?, ?, ?)",
                    task.taskId(), caller.department(), traceId, task.createdAt().toEpochMilli());
        });
    }

    /** 每次最多领取一条; 并发限制和租户轮转在数据库互斥行保护下对所有副本生效. */
    public ReadOnlyTaskClaimReply claim(String workerId) {
        return tx.execute(status -> {
            String cursor = jdbc.queryForObject(
                    "SELECT LAST_TENANT FROM ASYNC_TASK_DISPATCH_LOCK WHERE LOCK_ID=1 FOR UPDATE", String.class);
            long now = Instant.now().toEpochMilli();
            Long active = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM ASYNC_TASK t JOIN ASYNC_TASK_DISPATCH d ON t.TASK_ID=d.TASK_ID
                    WHERE t.STATUS IN ('PENDING','RUNNING') AND t.LEASE_EXPIRES_AT>?""", Long.class, now);
            if (active != null && active >= globalLimit) return null;
            // 一次清理至多16个耗尽候选, 避免坏任务造成无界事务或挡住其他租户.
            for (int scanned = 0; scanned < 16; scanned++) {
                List<String> candidates = jdbc.queryForList("""
                        SELECT t.TASK_ID FROM ASYNC_TASK t JOIN ASYNC_TASK_DISPATCH d ON t.TASK_ID=d.TASK_ID
                        WHERE t.STATUS IN ('PENDING','RUNNING')
                          AND (t.LEASE_EXPIRES_AT IS NULL OR t.LEASE_EXPIRES_AT<=?)
                          AND (SELECT COUNT(*) FROM ASYNC_TASK b JOIN ASYNC_TASK_DISPATCH bd ON b.TASK_ID=bd.TASK_ID
                            WHERE b.TENANT_ID=t.TENANT_ID AND b.STATUS IN ('PENDING','RUNNING')
                              AND b.LEASE_EXPIRES_AT>?) < ?
                        ORDER BY CASE WHEN t.TENANT_ID>? THEN 0 ELSE 1 END, t.TENANT_ID, t.CREATED_AT, t.TASK_ID
                        LIMIT 1""", String.class, now, now, tenantLimit, cursor == null ? "" : cursor);
                if (candidates.isEmpty()) return null;
                String id = candidates.getFirst();
                var leased = store.lease(id, workerId, Instant.ofEpochMilli(now).plusSeconds(leaseSeconds), null);
                if (!leased.acquired()) continue;
                AsyncTask task = leased.task();
                Integer attempts = jdbc.queryForObject("SELECT ATTEMPTS FROM ASYNC_TASK_DISPATCH WHERE TASK_ID=?", Integer.class, id);
                if (attempts != null && attempts >= MAX_RECOVERY_ATTEMPTS || task.createdAt().isBefore(Instant.ofEpochMilli(now).minusSeconds(MAX_RECOVERY_AGE_SECONDS))) {
                    store.transition(id, task.tenantId(), workerId, task.leaseEpoch(), AsyncTaskStatus.FAILED,
                            null, "ASYNC_TASK_RECOVERY_EXHAUSTED");
                    continue;
                }
                jdbc.update("UPDATE ASYNC_TASK_DISPATCH SET ATTEMPTS=ATTEMPTS+1 WHERE TASK_ID=?", id);
                jdbc.update("UPDATE ASYNC_TASK_DISPATCH_LOCK SET LAST_CLAIM_AT=?, LAST_TENANT=? WHERE LOCK_ID=1", now, task.tenantId());
                var row = jdbc.queryForMap("SELECT DEPARTMENT, TRACE_ID FROM ASYNC_TASK_DISPATCH WHERE TASK_ID=?", id);
                String credential = tokens.mint(new TenantContext.Tenant(task.tenantId(), task.userId(),
                        Set.of("agent"), (String) row.get("DEPARTMENT")));
                return new ReadOnlyTaskClaimReply(task, credential, (String) row.get("TRACE_ID"));
            }
            return null;
        });
    }
}
