package com.lrj.platform.auth;

import java.util.function.Supplier;

/**
 * RBAC 控制面复合写的执行器：把"读当前态 → 校验 → 多表写"的一段逻辑作为一个<b>原子单元</b>执行。
 * 只覆盖低频的注册与 admin 写，不覆盖登录读——以控制面吞吐换取跨记录/跨 store 的确定性（如
 * createIfAbsent + issueFor 同事务、最后管理员保护不被并发绕过）。
 *
 * <ul>
 *   <li>内存实现：全局临界区（synchronized）串行化控制面写。</li>
 *   <li>JDBC 实现：{@code TransactionTemplate(authTransactionManager)} 事务；各 Jdbc*Store 的
 *       JdbcTemplate 共用同一 DataSource，自动加入该事务，实现跨表原子提交/回滚。</li>
 * </ul>
 */
public interface RbacMutationExecutor {

    <T> T execute(Supplier<T> action);

    default void run(Runnable action) {
        execute(() -> {
            action.run();
            return null;
        });
    }
}
