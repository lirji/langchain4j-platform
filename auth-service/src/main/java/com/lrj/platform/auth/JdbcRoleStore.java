package com.lrj.platform.auth;

import com.lrj.platform.auth.mapper.RoleStoreMapper;
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
 * JDBC 角色存储（{@code AUTH_STORE=jdbc}）。schema 由独立版本化 migration 管理；
 * 业务进程启动时只校验当前 contract。
 *
 * <p>RBAC 关系化：角色→scope 的权威数据在关系表 {@code ROLE_SCOPE}（精确成员，供正确的成员判定）；
 * 旧 {@code ROLES.SCOPES} CSV 列保留一个版本作<b>影子双写</b>，便于回滚与兼容读。首次启动把 CSV 幂等
 * 回填进 ROLE_SCOPE。不建外键（跨 store 建表顺序 + H2/MySQL 方言差异），引用完整性由服务层保证。
 */
@Component
@ConditionalOnProperty(name = "app.auth.store", havingValue = "jdbc")
public class JdbcRoleStore implements RoleStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcRoleStore.class);

    private final RoleStoreMapper db;

    public JdbcRoleStore(DataSource authDataSource, AuthProperties props) {
        this.db = PlusMapperFactory.create(authDataSource, RoleStoreMapper.class);
        init(props);
    }

    private void init(AuthProperties props) {
        db.readInit1();
        db.readInit2();
        Integer count = Rows.one(db.readInit3());
        if ((count == null || count == 0) && props.getSeed().isEnabled()) {
            long now = System.currentTimeMillis();
            for (Role r : SeedRoles.defaults()) {
                insertRole(r, now);
            }
            log.info("ROLES/ROLE_SCOPE seeded with {} default roles (jdbc)", SeedRoles.defaults().size());
        }
        log.info("ROLES/ROLE_SCOPE schema verified");
    }

    @Override
    public Optional<Role> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        List<Role> rows = Rows.map(db.readFindByName1(key), (rs, i) -> new Role(rs.name, scopesOf(rs.name), rs.description));
        return rows.stream().findFirst();
    }

    @Override
    public List<Role> findAll() {
        return Rows.map(db.readFindAll1(), (rs, i) -> new Role(rs.name, scopesOf(rs.name), rs.description));
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
        return doUpdate(r, null);
    }

    @Override
    public long versionOf(String name) {
        List<Long> v = db.readVersionOf1(name.trim().toLowerCase(Locale.ROOT));
        return v.isEmpty() ? -1L : v.get(0);
    }

    @Override
    public boolean updateIfVersion(Role r, long expectedVersion) {
        return doUpdate(r,
                expectedVersion);
    }

    /**
     * 角色更新共用实现：先条件更新 ROLES（影子 CSV + 版本），命中则重建 ROLE_SCOPE 关系行
     * （在 mutation executor 事务内与上面同批提交）。expectedVersion 非空时执行版本条件更新。
     */
    private boolean doUpdate(Role r, Long expectedVersion) {
        if (db.updateRole(String.join(",", r.scopes()), r.description(), r.name(), expectedVersion) == 0) {
            return false;
        }
        db.writeDoUpdate1(r.name());
        long now = System.currentTimeMillis();
        for (String scope : r.scopes()) {
            insertRoleScopeIfAbsent(r.name(), scope, now);
        }
        return true;
    }

    @Override
    public void delete(String name) {
        if (name != null && !name.isBlank()) {
            String key = name.trim().toLowerCase(Locale.ROOT);
            db.writeDelete1(key);
            db.writeDelete2(key);
        }
    }

    private void insertRole(Role r, long now) {
        db.writeInsertRole1(r.name(), String.join(",", r.scopes()), r.description(), now);
        for (String scope : r.scopes()) {
            insertRoleScopeIfAbsent(r.name(), scope, now);
        }
    }

    private boolean insertRoleScopeIfAbsent(String roleName, String scope, long now) {
        try {
            db.writeInsertRoleScopeIfAbsent1(roleName, scope, now);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private Set<String> scopesOf(String roleName) {
        List<String> scopes = db.readScopesOf1(roleName);
        return new LinkedHashSet<>(scopes);
    }

}
