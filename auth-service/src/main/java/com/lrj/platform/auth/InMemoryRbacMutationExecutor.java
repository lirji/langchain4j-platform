package com.lrj.platform.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 内存 mutation executor（默认）：全局锁串行化 RBAC 控制面写，使复合操作（读-校验-写）相对彼此原子，
 * 等价于 JDBC 变体的事务串行化语义。登录读不走这里，不受影响。
 */
@Component
@ConditionalOnProperty(name = "app.auth.store", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryRbacMutationExecutor implements RbacMutationExecutor {

    private final Object lock = new Object();

    @Override
    public <T> T execute(Supplier<T> action) {
        synchronized (lock) {
            return action.get();
        }
    }
}
