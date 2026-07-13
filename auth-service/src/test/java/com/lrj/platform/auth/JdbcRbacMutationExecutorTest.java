package com.lrj.platform.auth;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JDBC 控制面执行器的 SERIALIZABLE + 重试语义：序列化冲突重试、业务异常不重试、重试耗尽上抛。
 * 用真实 H2 + DataSourceTransactionManager（SERIALIZABLE 隔离），action 内主动抛异常验证控制流。
 */
class JdbcRbacMutationExecutorTest {

    private static JdbcRbacMutationExecutor executor() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:exec_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        return new JdbcRbacMutationExecutor(new DataSourceTransactionManager(ds));
    }

    @Test
    void retriesOnSerializationFailure_thenSucceeds() {
        JdbcRbacMutationExecutor exec = executor();
        AtomicInteger calls = new AtomicInteger();
        String r = exec.execute(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new CannotSerializeTransactionException("serialization"); // 首次序列化失败
            }
            return "ok";
        });
        assertThat(r).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(2); // 重试一次后成功
    }

    @Test
    void businessException_notRetried_propagates() {
        JdbcRbacMutationExecutor exec = executor();
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> exec.execute(() -> {
            calls.incrementAndGet();
            throw new AuthException(409, "last_admin", "不能移除最后一个启用的 role-admin 用户");
        })).isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(409));
        assertThat(calls.get()).isEqualTo(1); // 业务拒绝不重试
    }

    @Test
    void exhaustsRetries_thenRethrows() {
        JdbcRbacMutationExecutor exec = executor();
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> exec.execute(() -> {
            calls.incrementAndGet();
            throw new CannotSerializeTransactionException("always");
        })).isInstanceOf(CannotSerializeTransactionException.class);
        assertThat(calls.get()).isEqualTo(4); // MAX_ATTEMPTS
    }
}
