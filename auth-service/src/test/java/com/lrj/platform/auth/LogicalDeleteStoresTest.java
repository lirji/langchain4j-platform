package com.lrj.platform.auth;

import com.lrj.platform.migrations.SchemaMigrationRunner;
import com.lrj.platform.migrations.SchemaName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** 同一行为套件在H2及脚本隔离的MySQL库执行，验证墓碑、重建和旧版本拒绝。 */
class LogicalDeleteStoresTest {
    private DriverManagerDataSource source() {
        String url = System.getenv("MP_AUTH_TEST_DB_URL");
        if (url != null && !url.contains("/lc4j_mp_it_")) throw new IllegalArgumentException("isolated test schema required");
        var source = new DriverManagerDataSource(url == null ? "jdbc:h2:mem:soft-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1" : url,
                url == null ? "sa" : System.getenv("MP_TEST_DB_USER"), url == null ? "" : System.getenv("MP_TEST_DB_PASSWORD"));
        SchemaMigrationRunner.migrate(source, SchemaName.AUTH);
        return source;
    }

    @Test
    void deletingAndRecreatingUserKeepsTombstoneAndRejectsOldVersion() {
        var source = source(); var props = new AuthProperties(); props.getSeed().setEnabled(false);
        var users = new JdbcUserAccountStore(source, new PasswordHasher(), props);
        String name = "user-" + UUID.randomUUID();
        var user = new UserAccount(name, "hash", "acme", "u", Set.of("chat"), Set.of("reader"), true);
        var tx = new JdbcRbacMutationExecutor(new DataSourceTransactionManager(source));
        tx.execute(() -> { users.save(user); return null; });
        long version = users.versionOf(name);
        tx.execute(() -> { users.delete(name); return null; });
        var jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT DELETED FROM USERS WHERE USERNAME=?", Integer.class, name)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT DELETED FROM USER_ROLE WHERE USERNAME=?", Integer.class, name)).isEqualTo(1);
        assertThat(users.findByUsername(name)).isEmpty();
        assertThat(users.findByRole("reader")).noneMatch(row -> name.equals(row.username()));
        assertThat(users.updateProfileIfVersion(name, "other", "acme", Set.of(), true, version)).isFalse();
        tx.execute(() -> { users.save(user); return null; });
        assertThat(users.findByUsername(name)).contains(user);
        assertThat(users.versionOf(name)).isGreaterThan(version);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM USERS WHERE USERNAME=?", Integer.class, name)).isEqualTo(1);
        assertThat(users.replaceRolesIfVersion(name, Set.of(), version)).isFalse();
    }

    @Test
    void membershipsAndTenantPoliciesCanBeRemovedAndReadded() {
        var source = source(); var props = new AuthProperties(); props.getSeed().setEnabled(false);
        var roles = new JdbcRoleStore(source, props);
        var groups = new JdbcGroupStore(source);
        var memberships = new JdbcUserGroupStore(source);
        var policies = new JdbcTenantPolicyStore(source);
        String key = "role-" + UUID.randomUUID();
        var role = new Role(key, Set.of("chat"), "description");
        var tx = new JdbcRbacMutationExecutor(new DataSourceTransactionManager(source));
        tx.execute(() -> {
            assertThat(roles.createIfAbsent(role)).isTrue();
            assertThat(roles.updateIfVersion(role, 0)).isTrue();
            assertThat(roles.findByName(key).orElseThrow().scopes()).containsExactly("chat");
            assertThat(groups.createIfAbsent(new Group(key, "group", Set.of(key)))).isTrue();
            assertThat(groups.updateIfVersion(new Group(key, "updated", Set.of(key)), 0)).isTrue();
            memberships.replaceGroupsForUser(key, Set.of(key));
            memberships.removeAllForUser(key);
            memberships.replaceGroupsForUser(key, Set.of(key));
            assertThat(memberships.groupsOf(key)).containsExactly(key);
            assertThat(policies.replaceRolesIfVersion(key, Set.of(key), -1)).isTrue();
            long version = policies.versionOf(key);
            policies.clear(key);
            assertThat(policies.rolesOf(key)).isEmpty();
            assertThat(policies.replaceRolesIfVersion(key, Set.of(key), -1)).isTrue();
            assertThat(policies.versionOf(key)).isGreaterThan(version);
            assertThat(policies.replaceRolesIfVersion(key, Set.of(), version)).isFalse();
            roles.delete(key);
            assertThat(roles.findByName(key)).isEmpty();
            assertThat(roles.createIfAbsent(role)).isTrue();
            assertThat(roles.updateIfVersion(role, 0)).isFalse();
            return null;
        });
    }
}
