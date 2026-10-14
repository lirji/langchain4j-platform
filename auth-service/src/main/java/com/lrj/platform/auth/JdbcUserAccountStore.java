package com.lrj.platform.auth;

import com.lrj.platform.auth.mapper.UserAccountStoreMapper;
import com.lrj.platform.auth.mapper.UserAccountStoreRow;
import com.lrj.platform.persistence.PlusMapperFactory;
import com.lrj.platform.persistence.Rows;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
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

    private final UserAccountStoreMapper db;

    public JdbcUserAccountStore(DataSource authDataSource, PasswordHasher hasher, AuthProperties props) {
        this.db = PlusMapperFactory.create(authDataSource, UserAccountStoreMapper.class);
        init(hasher, props);
    }

    private void init(PasswordHasher hasher, AuthProperties props) {
        db.readInit1();
        db.readInit2();
        Integer count = Rows.one(db.readInit3());
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
        return Rows.map(db.findUsers1(username.trim().toLowerCase(Locale.ROOT)), this::mapUser)
                .stream().findFirst();
    }

    @Override
    public List<UserAccount> findAll() {
        return Rows.map(db.findUsers2(), this::mapUser);
    }

    @Override
    public List<UserAccount> findPage(int offset, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return Rows.map(db.findUsers3(limit, Math.max(0, offset)), this::mapUser);
    }

    @Override
    public int count() {
        Integer n = Rows.one(db.readCount1());
        return n == null ? 0 : n;
    }

    @Override
    public List<UserAccount> findByRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return List.of();
        }
        List<String> usernames = db.readFindByRole1(roleName.trim().toLowerCase(Locale.ROOT));
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
        return Rows.map(db.findUsers4(tenant.trim()), this::mapUser);
    }

    @Override
    public List<String> distinctTenants() {
        return db.readDistinctTenants1();
    }

    @Override
    public boolean touchVersionIfVersion(String username, long expectedVersion) {
        return db.writeTouchVersionIfVersion1(username.trim().toLowerCase(Locale.ROOT), expectedVersion) > 0;
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
        int updated = db.writeUpdateProfile1(
                passwordHash,
                tenant,
                String.join(",", UserAccount.normalize(directScopes)),
                enabled,
                username.trim().toLowerCase(Locale.ROOT));
        return updated > 0;
    }

    @Override
    public boolean replaceRoles(String username, Set<String> roles) {
        return doReplaceRoles(username.trim().toLowerCase(Locale.ROOT), roles, null);
    }

    @Override
    public long versionOf(String username) {
        List<Long> v = db.readVersionOf1(username.trim().toLowerCase(Locale.ROOT));
        return v.isEmpty() ? -1L : v.get(0);
    }

    @Override
    public boolean updateProfileIfVersion(String username, String tenant, String passwordHash,
                                          Set<String> directScopes, boolean enabled, long expectedVersion) {
        int updated = db.writeUpdateProfileIfVersion1(
                passwordHash,
                tenant,
                String.join(",", UserAccount.normalize(directScopes)),
                enabled,
                username.trim().toLowerCase(Locale.ROOT),
                expectedVersion);
        return updated > 0;
    }

    @Override
    public boolean replaceRolesIfVersion(String username, Set<String> roles, long expectedVersion) {
        return doReplaceRoles(username.trim().toLowerCase(Locale.ROOT), roles, expectedVersion);
    }

    /**
     * 全量替换角色的共用实现：先条件更新 USERS（影子 CSV + 版本），命中则重建 USER_ROLE 关系行。
     * expectedVersion 非空时执行版本条件更新。
     */
    private boolean doReplaceRoles(String key, Set<String> roles, Long expectedVersion) {
        Set<String> norm = UserAccount.normalize(roles);
        if (db.updateRoles(String.join(",", norm), key, expectedVersion) == 0) {
            return false;
        }
        db.writeDoReplaceRoles1(key);
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
            db.writeDelete1(key);
            db.writeDelete2(key);
        }
    }

    // ---- helpers ----

    private void insertUser(UserAccount u, long now) {
        String username = u.username().trim().toLowerCase(Locale.ROOT);
        db.writeInsertUser1(
                username,
                u.passwordHash(),
                u.tenant(),
                u.userId(),
                String.join(",", u.scopes()),
                String.join(",", u.roles()),
                u.enabled(),
                now);
        for (String r : u.roles()) {
            insertUserRoleIfAbsent(username, r, now);
        }
    }

    private boolean insertUserRoleIfAbsent(String username, String roleName, long now) {
        try {
            db.writeInsertUserRoleIfAbsent1(username, roleName, now);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private UserAccount mapUser(UserAccountStoreRow rs, int rowNum) {
        String username = rs.username;
        return new UserAccount(
                username,
                rs.passwordHash,
                rs.tenant,
                rs.userId,
                parseCsv(rs.scopes),
                rolesOf(username),
                Boolean.TRUE.equals(rs.enabled));
    }

    /** 角色以关系表 USER_ROLE 为权威（非 USERS.ROLES CSV）。 */
    private Set<String> rolesOf(String username) {
        List<String> roles = db.readRolesOf1(username);
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
