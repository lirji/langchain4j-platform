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
 * JDBC 用户组存储（{@code AUTH_STORE=jdbc}）。表结构演进沿用项目约定：{@code CREATE TABLE IF NOT EXISTS}
 * 字面量写在类里，无 Flyway。语义精确镜像 {@link JdbcRoleStore}——组→角色的权威数据在关系表 {@code GROUP_ROLE}。
 *
 * <p>实体表命名为 {@code AUTH_GROUP}（不用 {@code GROUP}/{@code GROUPS}——二者在 H2/MySQL 均为保留字，
 * 需转义才能作表名）。不建外键，引用完整性由服务层保证。
 */
@Component
@ConditionalOnProperty(name = "app.auth.store", havingValue = "jdbc")
public class JdbcGroupStore implements GroupStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcGroupStore.class);

    private final JdbcTemplate jdbc;

    public JdbcGroupStore(DataSource authDataSource) {
        this.jdbc = new JdbcTemplate(authDataSource);
        init();
    }

    private void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS AUTH_GROUP (
                  NAME VARCHAR(128) NOT NULL PRIMARY KEY,
                  DESCRIPTION VARCHAR(256),
                  VERSION BIGINT NOT NULL DEFAULT 0,
                  CREATED_AT BIGINT NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS GROUP_ROLE (
                  GROUP_NAME VARCHAR(128) NOT NULL,
                  ROLE_NAME VARCHAR(128) NOT NULL,
                  CREATED_AT BIGINT NOT NULL,
                  PRIMARY KEY (GROUP_NAME, ROLE_NAME)
                )""");
        try {
            jdbc.execute("CREATE INDEX IF NOT EXISTS IDX_GROUP_ROLE_ROLE ON GROUP_ROLE (ROLE_NAME)");
        } catch (org.springframework.dao.DataAccessException e) {
            log.debug("GROUP_ROLE role index not created (non-fatal): {}", e.getMessage());
        }
        log.info("AUTH_GROUP/GROUP_ROLE tables ready");
    }

    @Override
    public Optional<Group> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        List<Group> rows = jdbc.query(
                "SELECT NAME, DESCRIPTION FROM AUTH_GROUP WHERE NAME=?",
                (rs, i) -> new Group(rs.getString("NAME"), rs.getString("DESCRIPTION"), rolesOf(rs.getString("NAME"))),
                key);
        return rows.stream().findFirst();
    }

    @Override
    public List<Group> findAll() {
        return jdbc.query("SELECT NAME, DESCRIPTION FROM AUTH_GROUP ORDER BY NAME",
                (rs, i) -> new Group(rs.getString("NAME"), rs.getString("DESCRIPTION"), rolesOf(rs.getString("NAME"))));
    }

    @Override
    public List<Group> findByNames(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<Group> out = new ArrayList<>();
        for (String n : names) {
            findByName(n).ifPresent(out::add);
        }
        return out;
    }

    @Override
    public boolean createIfAbsent(Group group) {
        try {
            insertGroup(group, System.currentTimeMillis());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public long versionOf(String name) {
        List<Long> v = jdbc.queryForList("SELECT VERSION FROM AUTH_GROUP WHERE NAME=?",
                Long.class, name.trim().toLowerCase(Locale.ROOT));
        return v.isEmpty() ? -1L : v.get(0);
    }

    @Override
    public boolean updateIfVersion(Group g, long expectedVersion) {
        String key = g.name();
        if (jdbc.update("UPDATE AUTH_GROUP SET DESCRIPTION=?, VERSION=VERSION+1 WHERE NAME=? AND VERSION=?",
                g.description(), key, expectedVersion) == 0) {
            return false;
        }
        jdbc.update("DELETE FROM GROUP_ROLE WHERE GROUP_NAME=?", key);
        long now = System.currentTimeMillis();
        for (String r : g.roles()) {
            insertGroupRoleIfAbsent(key, r, now);
        }
        return true;
    }

    @Override
    public boolean touchVersionIfVersion(String name, long expectedVersion) {
        String key = name.trim().toLowerCase(Locale.ROOT);
        return jdbc.update("UPDATE AUTH_GROUP SET VERSION=VERSION+1 WHERE NAME=? AND VERSION=?",
                key, expectedVersion) > 0;
    }

    @Override
    public void delete(String name) {
        if (name != null && !name.isBlank()) {
            String key = name.trim().toLowerCase(Locale.ROOT);
            jdbc.update("DELETE FROM GROUP_ROLE WHERE GROUP_NAME=?", key);
            jdbc.update("DELETE FROM AUTH_GROUP WHERE NAME=?", key);
        }
    }

    @Override
    public List<String> groupsUsingRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return List.of();
        }
        return jdbc.queryForList("SELECT DISTINCT GROUP_NAME FROM GROUP_ROLE WHERE ROLE_NAME=? ORDER BY GROUP_NAME",
                String.class, roleName.trim().toLowerCase(Locale.ROOT));
    }

    private void insertGroup(Group g, long now) {
        jdbc.update("INSERT INTO AUTH_GROUP (NAME, DESCRIPTION, CREATED_AT) VALUES (?, ?, ?)",
                g.name(), g.description(), now);
        for (String r : g.roles()) {
            insertGroupRoleIfAbsent(g.name(), r, now);
        }
    }

    private void insertGroupRoleIfAbsent(String groupName, String roleName, long now) {
        try {
            jdbc.update("INSERT INTO GROUP_ROLE (GROUP_NAME, ROLE_NAME, CREATED_AT) VALUES (?, ?, ?)",
                    groupName, roleName, now);
        } catch (DuplicateKeyException ignored) {
            // 幂等
        }
    }

    private Set<String> rolesOf(String groupName) {
        List<String> roles = jdbc.queryForList(
                "SELECT ROLE_NAME FROM GROUP_ROLE WHERE GROUP_NAME=? ORDER BY ROLE_NAME", String.class, groupName);
        return new LinkedHashSet<>(roles);
    }
}
