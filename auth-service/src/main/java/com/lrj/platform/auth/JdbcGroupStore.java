package com.lrj.platform.auth;

import com.lrj.platform.auth.mapper.GroupStoreMapper;
import com.lrj.platform.persistence.PlusMapperFactory;
import com.lrj.platform.persistence.Rows;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
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
 * JDBC 用户组存储（{@code AUTH_STORE=jdbc}）。schema 由独立 migration 管理；
 * 组→角色的权威数据在关系表 {@code GROUP_ROLE}。
 *
 * <p>实体表命名为 {@code AUTH_GROUP}（不用 {@code GROUP}/{@code GROUPS}——二者在 H2/MySQL 均为保留字，
 * 需转义才能作表名）。不建外键，引用完整性由服务层保证。
 */
@Component
@ConditionalOnProperty(name = "app.auth.store", havingValue = "jdbc")
public class JdbcGroupStore implements GroupStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcGroupStore.class);

    private final GroupStoreMapper db;

    public JdbcGroupStore(DataSource authDataSource) {
        this.db = PlusMapperFactory.create(authDataSource, GroupStoreMapper.class);
        init();
    }

    private void init() {
        db.readInit1();
        db.readInit2();
        log.info("AUTH_GROUP/GROUP_ROLE schema verified");
    }

    @Override
    public Optional<Group> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        List<Group> rows = Rows.map(db.readFindByName1(key), (rs, i) -> new Group(rs.name, rs.description, rolesOf(rs.name)));
        return rows.stream().findFirst();
    }

    @Override
    public List<Group> findAll() {
        return Rows.map(db.readFindAll1(), (rs, i) -> new Group(rs.name, rs.description, rolesOf(rs.name)));
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
        List<Long> v = db.readVersionOf1(name.trim().toLowerCase(Locale.ROOT));
        return v.isEmpty() ? -1L : v.get(0);
    }

    @Override
    public boolean updateIfVersion(Group g, long expectedVersion) {
        String key = g.name();
        if (db.writeUpdateIfVersion1(g.description(), key, expectedVersion) == 0) {
            return false;
        }
        db.writeUpdateIfVersion2(key);
        long now = System.currentTimeMillis();
        for (String r : g.roles()) {
            insertGroupRoleIfAbsent(key, r, now);
        }
        return true;
    }

    @Override
    public boolean touchVersionIfVersion(String name, long expectedVersion) {
        String key = name.trim().toLowerCase(Locale.ROOT);
        return db.writeTouchVersionIfVersion1(key, expectedVersion) > 0;
    }

    @Override
    public void delete(String name) {
        if (name != null && !name.isBlank()) {
            String key = name.trim().toLowerCase(Locale.ROOT);
            db.writeDelete1(key);
            db.writeDelete2(key);
        }
    }

    @Override
    public List<String> groupsUsingRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return List.of();
        }
        return db.readGroupsUsingRole1(roleName.trim().toLowerCase(Locale.ROOT));
    }

    private void insertGroup(Group g, long now) {
        db.writeInsertGroup1(g.name(), g.description(), now);
        for (String r : g.roles()) {
            insertGroupRoleIfAbsent(g.name(), r, now);
        }
    }

    private void insertGroupRoleIfAbsent(String groupName, String roleName, long now) {
        try {
            db.writeInsertGroupRoleIfAbsent1(groupName, roleName, now);
        } catch (DuplicateKeyException ignored) {
            // 幂等
        }
    }

    private Set<String> rolesOf(String groupName) {
        List<String> roles = db.readRolesOf1(groupName);
        return new LinkedHashSet<>(roles);
    }
}
