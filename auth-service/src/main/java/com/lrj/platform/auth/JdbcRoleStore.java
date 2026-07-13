package com.lrj.platform.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * JDBC 角色存储（{@code AUTH_STORE=jdbc}）。表结构演进沿用项目约定：{@code CREATE TABLE IF NOT EXISTS}
 * 字面量写在类里，无 Flyway。
 *
 * <p>RBAC 关系化：角色→scope 的权威数据在关系表 {@code ROLE_SCOPE}（精确成员，供正确的成员判定）；
 * 旧 {@code ROLES.SCOPES} CSV 列保留一个版本作<b>影子双写</b>，便于回滚与兼容读。首次启动把 CSV 幂等
 * 回填进 ROLE_SCOPE。不建外键（跨 store 建表顺序 + H2/MySQL 方言差异），引用完整性由服务层保证。
 */
@Component
@ConditionalOnProperty(name = "app.auth.store", havingValue = "jdbc")
public class JdbcRoleStore implements RoleStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcRoleStore.class);

    private final JdbcTemplate jdbc;

    public JdbcRoleStore(DataSource authDataSource, AuthProperties props) {
        this.jdbc = new JdbcTemplate(authDataSource);
        init(props);
    }

    private void init(AuthProperties props) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ROLES (
                  NAME VARCHAR(128) NOT NULL PRIMARY KEY,
                  SCOPES VARCHAR(1024),
                  DESCRIPTION VARCHAR(256),
                  CREATED_AT BIGINT NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ROLE_SCOPE (
                  ROLE_NAME VARCHAR(128) NOT NULL,
                  SCOPE VARCHAR(128) NOT NULL,
                  CREATED_AT BIGINT NOT NULL,
                  PRIMARY KEY (ROLE_NAME, SCOPE)
                )""");
        // 乐观锁版本列（加法迁移，幂等）。旧行 DEFAULT 0；旧代码不引用可忽略，回滚安全。
        addColumnIfMissing("VERSION", "BIGINT NOT NULL DEFAULT 0");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ROLES", Integer.class);
        if ((count == null || count == 0) && props.getSeed().isEnabled()) {
            long now = System.currentTimeMillis();
            for (Role r : SeedRoles.defaults()) {
                insertRole(r, now);
            }
            log.info("ROLES/ROLE_SCOPE seeded with {} default roles (jdbc)", SeedRoles.defaults().size());
        }
        backfillRoleScopeFromCsv();
        log.info("ROLES/ROLE_SCOPE tables ready");
    }

    /** 幂等回填：把 ROLES.SCOPES CSV 拆进 ROLE_SCOPE（仅补缺失行），支持早期 CSV 库无损升级。 */
    private void backfillRoleScopeFromCsv() {
        List<Object[]> rows = jdbc.query("SELECT NAME, SCOPES FROM ROLES", (rs, i) ->
                new Object[]{rs.getString("NAME"), rs.getString("SCOPES")});
        long now = System.currentTimeMillis();
        int migrated = 0;
        for (Object[] row : rows) {
            String name = (String) row[0];
            for (String scope : parseCsv((String) row[1])) {
                if (insertRoleScopeIfAbsent(name, scope, now)) {
                    migrated++;
                }
            }
        }
        if (migrated > 0) {
            log.info("backfilled {} ROLE_SCOPE rows from legacy ROLES.SCOPES CSV", migrated);
        }
    }

    @Override
    public Optional<Role> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        List<Role> rows = jdbc.query(
                "SELECT NAME, DESCRIPTION FROM ROLES WHERE NAME=?",
                (rs, i) -> new Role(rs.getString("NAME"), scopesOf(rs.getString("NAME")), rs.getString("DESCRIPTION")),
                key);
        return rows.stream().findFirst();
    }

    @Override
    public List<Role> findAll() {
        return jdbc.query("SELECT NAME, DESCRIPTION FROM ROLES ORDER BY NAME",
                (rs, i) -> new Role(rs.getString("NAME"), scopesOf(rs.getString("NAME")), rs.getString("DESCRIPTION")));
    }

    @Override
    public List<Role> findByNames(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<Role> out = new ArrayList<>();
        for (String n : names) {
            findByName(n).ifPresent(out::add);
        }
        return out;
    }

    @Override
    public void save(Role role) {
        // 旧 upsert 语义：存在则更新，否则插入（不再 delete+insert）。
        if (!update(role)) {
            createIfAbsent(role);
        }
    }

    @Override
    public boolean createIfAbsent(Role role) {
        try {
            insertRole(role, System.currentTimeMillis());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public boolean update(Role r) {
        return doUpdate(r, "UPDATE ROLES SET SCOPES=?, DESCRIPTION=?, VERSION=VERSION+1 WHERE NAME=?");
    }

    @Override
    public long versionOf(String name) {
        List<Long> v = jdbc.queryForList("SELECT VERSION FROM ROLES WHERE NAME=?",
                Long.class, name.trim().toLowerCase(Locale.ROOT));
        return v.isEmpty() ? -1L : v.get(0);
    }

    @Override
    public boolean updateIfVersion(Role r, long expectedVersion) {
        return doUpdate(r, "UPDATE ROLES SET SCOPES=?, DESCRIPTION=?, VERSION=VERSION+1 WHERE NAME=? AND VERSION=?",
                expectedVersion);
    }

    /**
     * 角色更新共用实现：先条件更新 ROLES（影子 CSV + 版本），命中则重建 ROLE_SCOPE 关系行
     * （在 mutation executor 事务内与上面同批提交）。{@code extraArgs} 供乐观锁变体传 expectedVersion。
     */
    private boolean doUpdate(Role r, String updateSql, Object... extraArgs) {
        Object[] args = new Object[3 + extraArgs.length];
        args[0] = String.join(",", r.scopes());
        args[1] = r.description();
        args[2] = r.name();
        System.arraycopy(extraArgs, 0, args, 3, extraArgs.length);
        if (jdbc.update(updateSql, args) == 0) {
            return false;
        }
        jdbc.update("DELETE FROM ROLE_SCOPE WHERE ROLE_NAME=?", r.name());
        long now = System.currentTimeMillis();
        for (String scope : r.scopes()) {
            insertRoleScopeIfAbsent(r.name(), scope, now);
        }
        return true;
    }

    /** 幂等加列：只吞"列已存在"，其余照抛（不掩盖真实 DDL 故障）。与 JdbcUserAccountStore 同策略。 */
    private void addColumnIfMissing(String column, String type) {
        try {
            jdbc.execute("ALTER TABLE ROLES ADD COLUMN " + column + " " + type);
            log.info("ROLES table: added column {}", column);
        } catch (org.springframework.dao.DataAccessException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                String msg = t.getMessage();
                if (msg != null) {
                    String m = msg.toLowerCase(Locale.ROOT);
                    if (m.contains("duplicate column") || m.contains("already exists")
                            || (m.contains("duplicate") && m.contains("column"))) {
                        return;
                    }
                }
            }
            throw e;
        }
    }

    @Override
    public void delete(String name) {
        if (name != null && !name.isBlank()) {
            String key = name.trim().toLowerCase(Locale.ROOT);
            jdbc.update("DELETE FROM ROLE_SCOPE WHERE ROLE_NAME=?", key);
            jdbc.update("DELETE FROM ROLES WHERE NAME=?", key);
        }
    }

    private void insertRole(Role r, long now) {
        jdbc.update("INSERT INTO ROLES (NAME, SCOPES, DESCRIPTION, CREATED_AT) VALUES (?, ?, ?, ?)",
                r.name(), String.join(",", r.scopes()), r.description(), now);
        for (String scope : r.scopes()) {
            insertRoleScopeIfAbsent(r.name(), scope, now);
        }
    }

    private boolean insertRoleScopeIfAbsent(String roleName, String scope, long now) {
        try {
            jdbc.update("INSERT INTO ROLE_SCOPE (ROLE_NAME, SCOPE, CREATED_AT) VALUES (?, ?, ?)",
                    roleName, scope, now);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private Set<String> scopesOf(String roleName) {
        List<String> scopes = jdbc.queryForList(
                "SELECT SCOPE FROM ROLE_SCOPE WHERE ROLE_NAME=? ORDER BY SCOPE", String.class, roleName);
        return new LinkedHashSet<>(scopes);
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
