package com.lrj.platform.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * JDBC 账号存储（{@code AUTH_STORE=jdbc}）。版本化 schema 由独立 {@code database-migrations}
 * Job 在发布前演进；业务进程启动时只验证必需表/列，绝不执行 DDL。
 *
 * <p>RBAC 关系化：用户→角色的权威数据在关系表 {@code USER_ROLE}（供正确的按角色反查——CSV LIKE 会误命中
 * 子串）；旧 {@code USERS.ROLES} CSV 列保留作<b>影子双写</b>便于回滚/兼容读。直配 {@code SCOPES} 仍存
 * USERS.SCOPES（正向读，无需关系化）。旧 CSV 的幂等回填由 auth migration 完成。不建外键（方言/顺序风险），
 * 引用完整性由服务层保证。复合写（用户+角色+refresh）的原子性由 {@code RbacMutationExecutor} 事务提供。
 */
@Component
@ConditionalOnProperty(name = "app.auth.store", havingValue = "jdbc")
public class JdbcUserAccountStore implements UserAccountStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcUserAccountStore.class);

    private final JdbcTemplate jdbc;

    public JdbcUserAccountStore(DataSource authDataSource, PasswordHasher hasher, AuthProperties props) {
        this.jdbc = new JdbcTemplate(authDataSource);
        init(hasher, props);
    }

    private void init(PasswordHasher hasher, AuthProperties props) {
        jdbc.queryForList("""
                SELECT USERNAME, PASSWORD_HASH, TENANT, USER_ID, SCOPES, ROLES, ENABLED, VERSION, CREATED_AT
                FROM USERS WHERE 1=0""");
        jdbc.queryForList("SELECT USERNAME, ROLE_NAME, CREATED_AT FROM USER_ROLE WHERE 1=0");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM USERS", Integer.class);
        if ((count == null || count == 0) && props.getSeed().isEnabled()) {
            for (UserAccount u : SeedUsers.defaults(hasher, props.getDemoPassword())) {
                insertUser(u, System.currentTimeMillis());
            }
            log.info("USERS/USER_ROLE seeded with demo accounts (jdbc)");
        }
        log.info("USERS/USER_ROLE schema verified");
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return loadUsers("SELECT * FROM USERS WHERE USERNAME=?", username.trim().toLowerCase(Locale.ROOT))
                .stream().findFirst();
    }

    @Override
    public List<UserAccount> findAll() {
        return loadUsers("SELECT * FROM USERS ORDER BY USERNAME");
    }

    @Override
    public List<UserAccount> findPage(int offset, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return loadUsers("SELECT * FROM USERS ORDER BY USERNAME LIMIT ? OFFSET ?",
                limit, Math.max(0, offset));
    }

    @Override
    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM USERS", Integer.class);
        return n == null ? 0 : n;
    }

    @Override
    public List<UserAccount> findByRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return List.of();
        }
        List<String> usernames = jdbc.queryForList(
                "SELECT USERNAME FROM USER_ROLE WHERE ROLE_NAME=? ORDER BY USERNAME",
                String.class, roleName.trim().toLowerCase(Locale.ROOT));
        List<UserAccount> out = new java.util.ArrayList<>();
        for (String u : usernames) {
            findByUsername(u).ifPresent(out::add);
        }
        return out;
    }

    @Override
    public List<UserAccount> findByTenant(String tenant) {
        if (tenant == null || tenant.isBlank()) {
            return List.of();
        }
        return loadUsers("SELECT * FROM USERS WHERE TENANT=? ORDER BY USERNAME", tenant.trim());
    }

    @Override
    public List<String> distinctTenants() {
        return jdbc.queryForList("SELECT DISTINCT TENANT FROM USERS WHERE TENANT IS NOT NULL ORDER BY TENANT",
                String.class);
    }

    @Override
    public boolean touchVersionIfVersion(String username, long expectedVersion) {
        return jdbc.update("UPDATE USERS SET VERSION=VERSION+1 WHERE USERNAME=? AND VERSION=?",
                username.trim().toLowerCase(Locale.ROOT), expectedVersion) > 0;
    }

    @Override
    public boolean createIfAbsent(UserAccount u) {
        try {
            insertUser(u, System.currentTimeMillis());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public boolean updateProfile(String username, String tenant, String passwordHash,
                                 Set<String> directScopes, boolean enabled) {
        int updated = jdbc.update(
                "UPDATE USERS SET PASSWORD_HASH=?, TENANT=?, SCOPES=?, ENABLED=?, VERSION=VERSION+1 WHERE USERNAME=?",
                passwordHash, tenant, String.join(",", UserAccount.normalize(directScopes)), enabled,
                username.trim().toLowerCase(Locale.ROOT));
        return updated > 0;
    }

    @Override
    public boolean replaceRoles(String username, Set<String> roles) {
        return doReplaceRoles(username.trim().toLowerCase(Locale.ROOT), roles,
                "UPDATE USERS SET ROLES=?, VERSION=VERSION+1 WHERE USERNAME=?");
    }

    @Override
    public long versionOf(String username) {
        List<Long> v = jdbc.queryForList("SELECT VERSION FROM USERS WHERE USERNAME=?",
                Long.class, username.trim().toLowerCase(Locale.ROOT));
        return v.isEmpty() ? -1L : v.get(0);
    }

    @Override
    public boolean updateProfileIfVersion(String username, String tenant, String passwordHash,
                                          Set<String> directScopes, boolean enabled, long expectedVersion) {
        int updated = jdbc.update(
                "UPDATE USERS SET PASSWORD_HASH=?, TENANT=?, SCOPES=?, ENABLED=?, VERSION=VERSION+1 "
                        + "WHERE USERNAME=? AND VERSION=?",
                passwordHash, tenant, String.join(",", UserAccount.normalize(directScopes)), enabled,
                username.trim().toLowerCase(Locale.ROOT), expectedVersion);
        return updated > 0;
    }

    @Override
    public boolean replaceRolesIfVersion(String username, Set<String> roles, long expectedVersion) {
        return doReplaceRoles(username.trim().toLowerCase(Locale.ROOT), roles,
                "UPDATE USERS SET ROLES=?, VERSION=VERSION+1 WHERE USERNAME=? AND VERSION=?", expectedVersion);
    }

    /**
     * 全量替换角色的共用实现：先条件更新 USERS（影子 CSV + 版本），命中则重建 USER_ROLE 关系行。
     * {@code extraArgs} 供带 {@code AND VERSION=?} 的乐观锁变体传 expectedVersion。
     */
    private boolean doReplaceRoles(String key, Set<String> roles, String updateSql, Object... extraArgs) {
        Set<String> norm = UserAccount.normalize(roles);
        Object[] args = new Object[2 + extraArgs.length];
        args[0] = String.join(",", norm);
        args[1] = key;
        System.arraycopy(extraArgs, 0, args, 2, extraArgs.length);
        if (jdbc.update(updateSql, args) == 0) {
            return false;
        }
        jdbc.update("DELETE FROM USER_ROLE WHERE USERNAME=?", key);
        long now = System.currentTimeMillis();
        for (String r : norm) {
            insertUserRoleIfAbsent(key, r, now);
        }
        return true;
    }

    @Override
    public void save(UserAccount u) {
        // 旧 upsert 语义（存在则全量更新，否则插入）；不再 delete+insert。
        if (!createIfAbsent(u)) {
            update(u);
        }
    }

    @Override
    public void update(UserAccount u) {
        updateProfile(u.username(), u.tenant(), u.passwordHash(), u.scopes(), u.enabled());
        replaceRoles(u.username(), u.roles());
    }

    @Override
    public void delete(String username) {
        if (username != null && !username.isBlank()) {
            String key = username.trim().toLowerCase(Locale.ROOT);
            jdbc.update("DELETE FROM USER_ROLE WHERE USERNAME=?", key);
            jdbc.update("DELETE FROM USERS WHERE USERNAME=?", key);
        }
    }

    // ---- helpers ----

    private void insertUser(UserAccount u, long now) {
        String username = u.username().trim().toLowerCase(Locale.ROOT);
        jdbc.update("""
                INSERT INTO USERS (USERNAME, PASSWORD_HASH, TENANT, USER_ID, SCOPES, ROLES, ENABLED, CREATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                username, u.passwordHash(), u.tenant(), u.userId(),
                String.join(",", u.scopes()), String.join(",", u.roles()), u.enabled(), now);
        for (String r : u.roles()) {
            insertUserRoleIfAbsent(username, r, now);
        }
    }

    private boolean insertUserRoleIfAbsent(String username, String roleName, long now) {
        try {
            jdbc.update("INSERT INTO USER_ROLE (USERNAME, ROLE_NAME, CREATED_AT) VALUES (?, ?, ?)",
                    username, roleName, now);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private List<UserAccount> loadUsers(String sql, Object... args) {
        return jdbc.query(sql, this::mapUser, args);
    }

    private UserAccount mapUser(ResultSet rs, int rowNum) throws SQLException {
        String username = rs.getString("USERNAME");
        return new UserAccount(
                username,
                rs.getString("PASSWORD_HASH"),
                rs.getString("TENANT"),
                rs.getString("USER_ID"),
                parseCsv(rs.getString("SCOPES")),
                rolesOf(username),
                rs.getBoolean("ENABLED"));
    }

    /** 角色以关系表 USER_ROLE 为权威（非 USERS.ROLES CSV）。 */
    private Set<String> rolesOf(String username) {
        List<String> roles = jdbc.queryForList(
                "SELECT ROLE_NAME FROM USER_ROLE WHERE USERNAME=? ORDER BY ROLE_NAME", String.class, username);
        return new LinkedHashSet<>(roles);
    }

    private static Set<String> parseCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
