package com.lrj.platform.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * JDBC 用户↔组成员存储（{@code AUTH_STORE=jdbc}）。schema 由独立 migration 管理；
 * 语义镜像 {@code USER_ROLE} 的关系化写/反查。
 */
@Component
@ConditionalOnProperty(name = "app.auth.store", havingValue = "jdbc")
public class JdbcUserGroupStore implements UserGroupStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcUserGroupStore.class);

    private final JdbcTemplate jdbc;

    public JdbcUserGroupStore(DataSource authDataSource) {
        this.jdbc = new JdbcTemplate(authDataSource);
        init();
    }

    private void init() {
        jdbc.queryForList("SELECT USERNAME, GROUP_NAME, CREATED_AT FROM USER_GROUP WHERE 1=0");
        log.info("USER_GROUP schema verified");
    }

    @Override
    public Set<String> groupsOf(String username) {
        if (username == null || username.isBlank()) {
            return Set.of();
        }
        List<String> groups = jdbc.queryForList(
                "SELECT GROUP_NAME FROM USER_GROUP WHERE USERNAME=? ORDER BY GROUP_NAME",
                String.class, username.trim().toLowerCase(Locale.ROOT));
        return new LinkedHashSet<>(groups);
    }

    @Override
    public List<String> membersOf(String group) {
        if (group == null || group.isBlank()) {
            return List.of();
        }
        return jdbc.queryForList("SELECT USERNAME FROM USER_GROUP WHERE GROUP_NAME=? ORDER BY USERNAME",
                String.class, group.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public void replaceGroupsForUser(String username, Set<String> groups) {
        String u = username.trim().toLowerCase(Locale.ROOT);
        jdbc.update("DELETE FROM USER_GROUP WHERE USERNAME=?", u);
        long now = System.currentTimeMillis();
        for (String g : normalize(groups)) {
            insertIfAbsent(u, g, now);
        }
    }

    @Override
    public void replaceMembersForGroup(String group, Set<String> members) {
        String g = group.trim().toLowerCase(Locale.ROOT);
        jdbc.update("DELETE FROM USER_GROUP WHERE GROUP_NAME=?", g);
        long now = System.currentTimeMillis();
        for (String m : normalize(members)) {
            insertIfAbsent(m, g, now);
        }
    }

    @Override
    public void removeAllForUser(String username) {
        if (username != null && !username.isBlank()) {
            jdbc.update("DELETE FROM USER_GROUP WHERE USERNAME=?", username.trim().toLowerCase(Locale.ROOT));
        }
    }

    @Override
    public void removeAllForGroup(String group) {
        if (group != null && !group.isBlank()) {
            jdbc.update("DELETE FROM USER_GROUP WHERE GROUP_NAME=?", group.trim().toLowerCase(Locale.ROOT));
        }
    }

    private void insertIfAbsent(String username, String group, long now) {
        try {
            jdbc.update("INSERT INTO USER_GROUP (USERNAME, GROUP_NAME, CREATED_AT) VALUES (?, ?, ?)",
                    username, group, now);
        } catch (DuplicateKeyException ignored) {
            // 幂等
        }
    }

    private static Set<String> normalize(Set<String> in) {
        Set<String> out = new LinkedHashSet<>();
        if (in != null) {
            for (String s : in) {
                if (s != null && !s.isBlank()) {
                    out.add(s.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }
}
