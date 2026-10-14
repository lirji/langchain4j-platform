package com.lrj.platform.auth;

import com.lrj.platform.auth.mapper.UserGroupStoreMapper;
import com.lrj.platform.persistence.PlusMapperFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
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

    private final UserGroupStoreMapper db;

    public JdbcUserGroupStore(DataSource authDataSource) {
        this.db = PlusMapperFactory.create(authDataSource, UserGroupStoreMapper.class);
        init();
    }

    private void init() {
        db.readInit1();
        log.info("USER_GROUP schema verified");
    }

    @Override
    public Set<String> groupsOf(String username) {
        if (username == null || username.isBlank()) {
            return Set.of();
        }
        List<String> groups = db.readGroupsOf1(username.trim().toLowerCase(Locale.ROOT));
        return new LinkedHashSet<>(groups);
    }

    @Override
    public List<String> membersOf(String group) {
        if (group == null || group.isBlank()) {
            return List.of();
        }
        return db.readMembersOf1(group.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public void replaceGroupsForUser(String username, Set<String> groups) {
        String u = username.trim().toLowerCase(Locale.ROOT);
        db.writeReplaceGroupsForUser1(u);
        long now = System.currentTimeMillis();
        for (String g : normalize(groups)) {
            insertIfAbsent(u, g, now);
        }
    }

    @Override
    public void replaceMembersForGroup(String group, Set<String> members) {
        String g = group.trim().toLowerCase(Locale.ROOT);
        db.writeReplaceMembersForGroup1(g);
        long now = System.currentTimeMillis();
        for (String m : normalize(members)) {
            insertIfAbsent(m, g, now);
        }
    }

    @Override
    public void removeAllForUser(String username) {
        if (username != null && !username.isBlank()) {
            db.writeRemoveAllForUser1(username.trim().toLowerCase(Locale.ROOT));
        }
    }

    @Override
    public void removeAllForGroup(String group) {
        if (group != null && !group.isBlank()) {
            db.writeRemoveAllForGroup1(group.trim().toLowerCase(Locale.ROOT));
        }
    }

    private void insertIfAbsent(String username, String group, long now) {
        try {
            db.writeInsertIfAbsent1(username, group, now);
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
