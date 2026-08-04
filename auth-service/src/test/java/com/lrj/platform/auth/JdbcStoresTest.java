package com.lrj.platform.auth;

import com.lrj.platform.migrations.SchemaMigrationRunner;
import com.lrj.platform.migrations.SchemaName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JDBC 存储（H2 MySQL 模式）：使用预迁移 schema，验证种子、查询和刷新会话轮转。 */
class JdbcStoresTest {

    private final PasswordHasher hasher = new PasswordHasher();
    private final AuthProperties props = new AuthProperties();

    @Test
    void userStoreUsesMigratedSchemaAndSeedsDemoAccounts() {
        JdbcUserAccountStore store = new JdbcUserAccountStore(h2("auth_users"), hasher, props);

        Optional<UserAccount> alice = store.findByUsername("alice");
        assertThat(alice).isPresent();
        assertThat(alice.get().tenant()).isEqualTo("acme");
        assertThat(alice.get().scopes()).contains("chat", "ingest");
        assertThat(hasher.matches("demo12345", alice.get().passwordHash())).isTrue();

        // 大小写不敏感 + 未知用户
        assertThat(store.findByUsername("ALICE")).isPresent();
        assertThat(store.findByUsername("ghost")).isEmpty();
    }

    @Test
    void missingSchemaFailsWithoutCreatingTables() {
        DataSource dataSource = blankH2("auth_missing_schema");

        assertThatThrownBy(() -> new JdbcUserAccountStore(dataSource, hasher, props))
                .isInstanceOf(DataAccessException.class);
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME='USERS'",
                Integer.class)).isZero();
    }

    @Test
    void userStoreDoesNotReseedOnSecondBoot() {
        DataSource ds = h2("auth_users_reseed");
        new JdbcUserAccountStore(ds, hasher, props);
        // 二次构造（模拟重启）不应重复插入
        JdbcUserAccountStore again = new JdbcUserAccountStore(ds, hasher, props);
        assertThat(again.findByUsername("alice")).isPresent();
    }

    @Test
    void refreshSessionStoreCreateFindRevoke() {
        JdbcRefreshSessionStore store = new JdbcRefreshSessionStore(h2("auth_sessions"));
        Instant now = Instant.now();
        RefreshSession s = new RefreshSession("hash-1", "alice", now, now.plusSeconds(3600), false);
        store.create(s);

        Optional<RefreshSession> found = store.findByTokenHash("hash-1");
        assertThat(found).isPresent();
        assertThat(found.get().username()).isEqualTo("alice");
        assertThat(found.get().isActive(now)).isTrue();

        store.revoke("hash-1");
        assertThat(store.findByTokenHash("hash-1").get().revoked()).isTrue();
        assertThat(store.findByTokenHash("hash-1").get().isActive(now)).isFalse();
        assertThat(store.findByTokenHash("missing")).isEmpty();
    }

    private static DataSource h2(String dbName) {
        DataSource dataSource = blankH2(dbName);
        SchemaMigrationRunner.migrate(dataSource, SchemaName.AUTH);
        return dataSource;
    }

    private static DataSource blankH2(String dbName) {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + dbName + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }
}
