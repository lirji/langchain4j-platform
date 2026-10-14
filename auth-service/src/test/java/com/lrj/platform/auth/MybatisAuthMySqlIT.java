package com.lrj.platform.auth;

import com.lrj.platform.migrations.SchemaMigrationRunner;
import com.lrj.platform.migrations.SchemaName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.time.Instant;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

/** 在脚本独占的临时MySQL库验证不同Mapper共享事务、版本CAS及布尔/时间映射。 */
@EnabledIfEnvironmentVariable(named = "MP_AUTH_TEST_DB_URL", matches = ".+/lc4j_mp_it_.+")
class MybatisAuthMySqlIT {
    @Test
    void accountRolesAndSessionsCommitAndRollbackTogether() {
        var source = new DriverManagerDataSource(System.getenv("MP_AUTH_TEST_DB_URL"),
                System.getenv("MP_TEST_DB_USER"), System.getenv("MP_TEST_DB_PASSWORD"));
        SchemaMigrationRunner.migrate(source, SchemaName.AUTH);
        var props = new AuthProperties(); props.getSeed().setEnabled(false);
        var users = new JdbcUserAccountStore(source, new PasswordHasher(), props);
        var roles = new JdbcRoleStore(source, props);
        var sessions = new JdbcRefreshSessionStore(source);
        var mutation = new JdbcRbacMutationExecutor(new DataSourceTransactionManager(source));
        var account = new UserAccount("mp-user", "test-hash", "tenant-a", "uid", Set.of("chat"), Set.of("reader"), true);
        Instant now = Instant.ofEpochMilli(1780000000000L);
        mutation.execute(() -> {
            roles.createIfAbsent(new Role("reader", Set.of("chat"), "read"));
            users.save(account);
            sessions.create(new RefreshSession("hash", "mp-user", now, now.plusSeconds(3600), false));
            return null;
        });
        assertThat(users.findByUsername("MP-USER").orElseThrow()).isEqualTo(account);
        long version = users.versionOf("mp-user");
        assertThatThrownBy(() -> mutation.execute(() -> {
            assertThat(users.replaceRolesIfVersion("mp-user", Set.of(), version)).isTrue();
            sessions.revoke("hash");
            // 第二个Mapper必须看到事务内写入，外层异常后两张表都必须恢复。
            assertThat(users.findByUsername("mp-user").orElseThrow().roles()).isEmpty();
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(users.findByUsername("mp-user").orElseThrow().roles()).containsExactly("reader");
        assertThat(users.versionOf("mp-user")).isEqualTo(version);
        assertThat(sessions.findByTokenHash("hash").orElseThrow().createdAt()).isEqualTo(now);
        assertThat(sessions.findByTokenHash("hash").orElseThrow().revoked()).isFalse();
        mutation.execute(() -> users.replaceRolesIfVersion("mp-user", Set.of(), version));
        assertThat(mutation.execute(() -> users.replaceRolesIfVersion("mp-user", Set.of("reader"), version))).isFalse();
        assertThat(users.findByUsername("mp-user").orElseThrow().roles()).isEmpty();
    }
}
