package com.lrj.platform.auth;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RBAC 关系表迁移/回填（H2 MySQL 模式）：验证三种升级路径 + 幂等 + 按角色反查。
 * 覆盖 FINAL_PLAN 阶段 1 完成标准：main 基线 schema、早期 CSV schema、空库。
 */
class JdbcRbacMigrationTest {

    private final PasswordHasher hasher = new PasswordHasher();

    private static DataSource h2(String db) {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + db + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    private static AuthProperties noSeed() {
        AuthProperties p = new AuthProperties();
        p.getSeed().setEnabled(false);
        return p;
    }

    @Test
    void emptyDb_seedsUsersAndRelations() {
        JdbcUserAccountStore store = new JdbcUserAccountStore(h2("mig_empty"), hasher, new AuthProperties());
        UserAccount alice = store.findByUsername("alice").orElseThrow();
        // 角色以 USER_ROLE 关系表为权威
        assertThat(alice.roles()).containsExactly("admin");
        assertThat(alice.scopes()).contains("chat", "ingest");
        // 按角色反查（精确匹配，不是 CSV LIKE 子串）
        assertThat(store.findByRole("admin")).extracting(UserAccount::username).contains("alice");
        assertThat(store.count()).isEqualTo(3);
    }

    @Test
    void earlyCsvSchema_backfillsUserRoleFromCsv() {
        DataSource ds = h2("mig_csv");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        // 早期实现：USERS 已有 ROLES CSV 列且有数据，但无 USER_ROLE 关系表。
        jdbc.execute("""
                CREATE TABLE USERS (
                  USERNAME VARCHAR(128) NOT NULL PRIMARY KEY, PASSWORD_HASH VARCHAR(256) NOT NULL,
                  TENANT VARCHAR(128) NOT NULL, USER_ID VARCHAR(128) NOT NULL, SCOPES VARCHAR(1024),
                  ROLES VARCHAR(1024), ENABLED BOOLEAN NOT NULL, CREATED_AT BIGINT NOT NULL)""");
        jdbc.update("INSERT INTO USERS VALUES ('carol','h','acme','carol','chat','editor,viewer',TRUE,1)");

        JdbcUserAccountStore store = new JdbcUserAccountStore(ds, hasher, noSeed());
        UserAccount carol = store.findByUsername("carol").orElseThrow();
        assertThat(carol.roles()).containsExactlyInAnyOrder("editor", "viewer");
        assertThat(store.findByRole("editor")).extracting(UserAccount::username).containsExactly("carol");
        // 幂等：再次构造不重复插入、不报错
        JdbcUserAccountStore again = new JdbcUserAccountStore(ds, hasher, noSeed());
        assertThat(again.findByUsername("carol").orElseThrow().roles()).containsExactlyInAnyOrder("editor", "viewer");
    }

    @Test
    void mainBaselineSchema_withoutRolesColumn_upgradesCleanly() {
        DataSource ds = h2("mig_baseline");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        // main 基线：USERS 无 ROLES 列、无 USER_ROLE 表。
        jdbc.execute("""
                CREATE TABLE USERS (
                  USERNAME VARCHAR(128) NOT NULL PRIMARY KEY, PASSWORD_HASH VARCHAR(256) NOT NULL,
                  TENANT VARCHAR(128) NOT NULL, USER_ID VARCHAR(128) NOT NULL, SCOPES VARCHAR(1024),
                  ENABLED BOOLEAN NOT NULL, CREATED_AT BIGINT NOT NULL)""");
        jdbc.update("INSERT INTO USERS VALUES ('dave','h','globex','dave','chat',TRUE,1)");

        JdbcUserAccountStore store = new JdbcUserAccountStore(ds, hasher, noSeed());
        UserAccount dave = store.findByUsername("dave").orElseThrow();
        assertThat(dave.roles()).isEmpty();           // 无角色（direct-only 老用户仍合法）
        assertThat(dave.scopes()).containsExactly("chat");
        // ROLES 列已补齐，USER_ROLE 表已建
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM USER_ROLE", Integer.class)).isZero();
    }

    @Test
    void roleStore_backfillsRoleScopeFromCsv() {
        DataSource ds = h2("mig_rolescope");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE ROLES (NAME VARCHAR(128) NOT NULL PRIMARY KEY, SCOPES VARCHAR(1024),
                  DESCRIPTION VARCHAR(256), CREATED_AT BIGINT NOT NULL)""");
        jdbc.update("INSERT INTO ROLES VALUES ('custom','chat,ingest','自定义',1)");

        JdbcRoleStore store = new JdbcRoleStore(ds, noSeed());
        assertThat(store.findByName("custom").orElseThrow().scopes()).containsExactlyInAnyOrder("chat", "ingest");
        // 关系表已回填
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ROLE_SCOPE WHERE ROLE_NAME='custom'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void relationalWrites_createUpdateReplaceRoles_areAuthoritative() {
        JdbcUserAccountStore store = new JdbcUserAccountStore(h2("mig_writes"), hasher, noSeed());
        assertThat(store.createIfAbsent(new UserAccount("erin", "h", "acme", "erin",
                java.util.Set.of("chat"), java.util.Set.of("viewer"), true))).isTrue();
        // 重复建号不覆盖
        assertThat(store.createIfAbsent(new UserAccount("erin", "h2", "other", "erin",
                java.util.Set.of(), java.util.Set.of("admin"), true))).isFalse();
        assertThat(store.findByUsername("erin").orElseThrow().roles()).containsExactly("viewer");
        // 全量替换角色（幂等）
        assertThat(store.replaceRoles("erin", java.util.Set.of("editor", "approver"))).isTrue();
        assertThat(store.findByUsername("erin").orElseThrow().roles()).containsExactlyInAnyOrder("editor", "approver");
        assertThat(store.replaceRoles("ghost", java.util.Set.of("editor"))).isFalse();
        // 改资料不动角色
        assertThat(store.updateProfile("erin", "acme2", "h3", java.util.Set.of("chat", "analytics"), true)).isTrue();
        UserAccount erin = store.findByUsername("erin").orElseThrow();
        assertThat(erin.tenant()).isEqualTo("acme2");
        assertThat(erin.scopes()).containsExactlyInAnyOrder("chat", "analytics");
        assertThat(erin.roles()).containsExactlyInAnyOrder("editor", "approver");
    }

    @Test
    void versionColumn_optimisticLock_conditionalUpdate() {
        JdbcUserAccountStore store = new JdbcUserAccountStore(h2("mig_version"), hasher, noSeed());
        store.createIfAbsent(new UserAccount("vera", "h", "acme", "vera",
                java.util.Set.of("chat"), java.util.Set.of("viewer"), true));
        assertThat(store.versionOf("vera")).isEqualTo(0L);
        // 版本匹配 → 成功并前进
        assertThat(store.updateProfileIfVersion("vera", "acme2", "h", java.util.Set.of("chat"), true, 0L)).isTrue();
        assertThat(store.versionOf("vera")).isEqualTo(1L);
        // 陈旧版本 → 拒绝、不覆盖
        assertThat(store.updateProfileIfVersion("vera", "acme3", "h", java.util.Set.of("chat"), true, 0L)).isFalse();
        assertThat(store.findByUsername("vera").orElseThrow().tenant()).isEqualTo("acme2");
        // 非版本化写也前进版本（角色替换）
        assertThat(store.replaceRoles("vera", java.util.Set.of("editor"))).isTrue();
        assertThat(store.versionOf("vera")).isEqualTo(2L);
        // 版本化角色替换：陈旧版本被拒
        assertThat(store.replaceRolesIfVersion("vera", java.util.Set.of("approver"), 0L)).isFalse();
        assertThat(store.replaceRolesIfVersion("vera", java.util.Set.of("approver"), 2L)).isTrue();
    }

    @Test
    void roleVersionColumn_optimisticLock() {
        JdbcRoleStore store = new JdbcRoleStore(h2("mig_roleversion"), noSeed());
        store.createIfAbsent(new Role("support", java.util.Set.of("chat"), "客服"));
        assertThat(store.versionOf("support")).isEqualTo(0L);
        assertThat(store.updateIfVersion(new Role("support", java.util.Set.of("chat", "ingest"), "v1"), 0L)).isTrue();
        assertThat(store.versionOf("support")).isEqualTo(1L);
        assertThat(store.updateIfVersion(new Role("support", java.util.Set.of("chat"), "stale"), 0L)).isFalse();
        assertThat(store.findByName("support").orElseThrow().scopes()).containsExactlyInAnyOrder("chat", "ingest");
    }

    // ---- 继承层新表（H2 MySQL 模式）：建表 / 反查 / 乐观锁 ----

    @Test
    void tenantPolicyStore_replaceAndVersionAndReverseLookup() {
        JdbcTenantPolicyStore store = new JdbcTenantPolicyStore(h2("mig_tenant"));
        assertThat(store.versionOf("acme")).isEqualTo(-1L);          // 无策略行
        store.replaceRoles("acme", java.util.Set.of("viewer", "editor"));
        assertThat(store.versionOf("acme")).isEqualTo(0L);           // 首次绑定即建行、版本 0
        assertThat(store.rolesOf("acme")).containsExactlyInAnyOrder("viewer", "editor");
        store.replaceRoles("acme", java.util.Set.of("viewer"));
        assertThat(store.versionOf("acme")).isEqualTo(1L);           // 再次替换 +1
        assertThat(store.rolesOf("acme")).containsExactly("viewer");
        // 反查引用角色的租户
        assertThat(store.tenantsUsingRole("viewer")).containsExactly("acme");
        assertThat(store.tenantsUsingRole("editor")).isEmpty();
        store.clear("acme");
        assertThat(store.versionOf("acme")).isEqualTo(-1L);
        assertThat(store.rolesOf("acme")).isEmpty();
    }

    @Test
    void tenantPolicyStore_optimisticLock() {
        JdbcTenantPolicyStore store = new JdbcTenantPolicyStore(h2("mig_tenant_lock"));
        // 期望尚无行：expected=-1 首次绑定成功；再次 -1 失败（行已存在）
        assertThat(store.replaceRolesIfVersion("acme", java.util.Set.of("viewer"), -1L)).isTrue();
        assertThat(store.replaceRolesIfVersion("acme", java.util.Set.of("editor"), -1L)).isFalse();
        assertThat(store.replaceRolesIfVersion("acme", java.util.Set.of("editor"), 0L)).isTrue();
        assertThat(store.replaceRolesIfVersion("acme", java.util.Set.of("approver"), 0L)).isFalse();   // 陈旧
        assertThat(store.rolesOf("acme")).containsExactly("editor");
    }

    @Test
    void groupStore_crudVersionAndReverseLookup() {
        JdbcGroupStore store = new JdbcGroupStore(h2("mig_group"));
        assertThat(store.createIfAbsent(new Group("eng", "工程组", java.util.Set.of("editor")))).isTrue();
        assertThat(store.createIfAbsent(new Group("eng", "dup", java.util.Set.of("viewer")))).isFalse();
        assertThat(store.findByName("eng").orElseThrow().roles()).containsExactly("editor");
        assertThat(store.versionOf("eng")).isEqualTo(0L);
        assertThat(store.updateIfVersion(new Group("eng", "改", java.util.Set.of("editor", "analyst")), 0L)).isTrue();
        assertThat(store.versionOf("eng")).isEqualTo(1L);
        assertThat(store.updateIfVersion(new Group("eng", "stale", java.util.Set.of("viewer")), 0L)).isFalse();
        assertThat(store.findByName("eng").orElseThrow().roles()).containsExactlyInAnyOrder("editor", "analyst");
        // touchVersion 仅推进版本、不改内容
        assertThat(store.touchVersionIfVersion("eng", 1L)).isTrue();
        assertThat(store.versionOf("eng")).isEqualTo(2L);
        assertThat(store.findByName("eng").orElseThrow().roles()).containsExactlyInAnyOrder("editor", "analyst");
        // 反查引用角色的组
        assertThat(store.groupsUsingRole("analyst")).containsExactly("eng");
        store.delete("eng");
        assertThat(store.findByName("eng")).isEmpty();
    }

    @Test
    void userGroupStore_membershipWritesAndReverseLookup() {
        JdbcUserGroupStore store = new JdbcUserGroupStore(h2("mig_usergroup"));
        store.replaceGroupsForUser("alice", java.util.Set.of("eng", "ops"));
        assertThat(store.groupsOf("alice")).containsExactlyInAnyOrder("eng", "ops");
        assertThat(store.membersOf("eng")).containsExactly("alice");
        // 组侧全量替换成员
        store.replaceMembersForGroup("eng", java.util.Set.of("alice", "bob"));
        assertThat(store.membersOf("eng")).containsExactlyInAnyOrder("alice", "bob");
        assertThat(store.groupsOf("bob")).containsExactly("eng");
        // 级联清理
        store.removeAllForUser("alice");
        assertThat(store.groupsOf("alice")).isEmpty();
        assertThat(store.membersOf("eng")).containsExactly("bob");
        store.removeAllForGroup("eng");
        assertThat(store.membersOf("eng")).isEmpty();
        assertThat(store.groupsOf("bob")).isEmpty();
    }

    @Test
    void userAccountStore_tenantReadsAndVersionTouch() {
        JdbcUserAccountStore store = new JdbcUserAccountStore(h2("mig_tenantreads"), hasher, noSeed());
        store.createIfAbsent(new UserAccount("a", "h", "acme", "a", java.util.Set.of(), java.util.Set.of("viewer"), true));
        store.createIfAbsent(new UserAccount("b", "h", "acme", "b", java.util.Set.of(), java.util.Set.of(), true));
        store.createIfAbsent(new UserAccount("c", "h", "globex", "c", java.util.Set.of(), java.util.Set.of(), true));
        assertThat(store.distinctTenants()).containsExactly("acme", "globex");
        assertThat(store.findByTenant("acme")).extracting(UserAccount::username).containsExactlyInAnyOrder("a", "b");
        // touchVersion 仅推进版本、不改字段
        assertThat(store.versionOf("a")).isEqualTo(0L);
        assertThat(store.touchVersionIfVersion("a", 0L)).isTrue();
        assertThat(store.versionOf("a")).isEqualTo(1L);
        assertThat(store.touchVersionIfVersion("a", 0L)).isFalse();   // 陈旧
    }
}
