package com.lrj.platform.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * JDBC mutation executor（{@code AUTH_STORE=jdbc}）：把复合写包进 {@code authTransactionManager} 的
 * 单个事务。各 Jdbc*Store 的 JdbcTemplate 共用同一 auth DataSource，因而其写操作自动加入本事务，
 * 实现"用户 + USER_ROLE + refresh session"跨表原子提交/回滚（异常整体回滚）。
 *
 * <p><b>SERIALIZABLE + 重试</b>：控制面写用可串行化隔离，使"读当前态 →（最后管理员/引用完整性）校验 →
 * 多表写"相对并发确定——否则默认 READ_COMMITTED 下两个事务可各自读到"还有另一个管理员"、再分别降权而同时
 * 提交，最终归零（内存实现靠全局锁天然安全，JDBC 靠此对齐）。序列化/死锁失败会回滚，本执行器有界重试：
 * 重跑 action 会重读最新态、重判不变量，因此并发被拒的降权不会静默生效。业务异常（如 409 last_admin）
 * 不是并发失败，不重试、直接上抛。
 */
@Component
@ConditionalOnProperty(name = "app.auth.store", havingValue = "jdbc")
public class JdbcRbacMutationExecutor implements RbacMutationExecutor {

    private static final Logger log = LoggerFactory.getLogger(JdbcRbacMutationExecutor.class);
    /** 序列化冲突重试上限（含首次尝试）。控制面写低频，冲突罕见，少量重试足够。 */
    private static final int MAX_ATTEMPTS = 4;

    private final TransactionTemplate tx;

    public JdbcRbacMutationExecutor(PlatformTransactionManager authTransactionManager) {
        this.tx = new TransactionTemplate(authTransactionManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    @Override
    public <T> T execute(Supplier<T> action) {
        ConcurrencyFailureException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> action.get());
            } catch (ConcurrencyFailureException e) {
                // 序列化失败/死锁：事务已回滚，重读最新态再判不变量后重试。
                last = e;
                log.debug("rbac mutation serialization retry {}/{}: {}", attempt, MAX_ATTEMPTS, e.getMessage());
            }
        }
        throw last; // 重试耗尽（极罕见）：上抛由异常处理映射为 5xx，调用方可稍后重试。
    }
}
