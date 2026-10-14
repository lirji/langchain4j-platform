package com.lrj.platform.auth;

import com.lrj.platform.auth.mapper.TenantPolicyStoreMapper;
import com.lrj.platform.persistence.PlusMapperFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * JDBC 租户基础角色存储（{@code AUTH_STORE=jdbc}）。schema 由独立 migration 管理。
 *
 * <p>租户不是一等实体，故只建两张轻量表：{@code TENANT_POLICY} 承载"租户 → 乐观锁版本"（因租户无实体行，
 * 版本无处可挂，需此惰性行）；{@code TENANT_ROLE} 是"租户 → 基础角色名"的权威绑定。不建外键（方言/顺序），
 * 引用完整性由服务层保证。复合写（策略行 + 绑定行 + refresh 撤销）的原子性由 {@code RbacMutationExecutor} 事务提供。
 */
@Component
@ConditionalOnProperty(name = "app.auth.store", havingValue = "jdbc")
public class JdbcTenantPolicyStore implements TenantPolicyStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcTenantPolicyStore.class);

    private final TenantPolicyStoreMapper db;

    public JdbcTenantPolicyStore(DataSource authDataSource) {
        this.db = PlusMapperFactory.create(authDataSource, TenantPolicyStoreMapper.class);
        init();
    }

    private void init() {
        db.readInit1();
        db.readInit2();
        log.info("TENANT_POLICY/TENANT_ROLE schema verified");
    }

    @Override
    public Set<String> rolesOf(String tenant) {
        if (tenant == null || tenant.isBlank()) {
            return Set.of();
        }
        List<String> roles = db.readRolesOf1(tenant.trim());
        return new LinkedHashSet<>(roles);
    }

    @Override
    public long versionOf(String tenant) {
        List<Long> v = db.readVersionOf1(tenant.trim());
        return v.isEmpty() ? -1L : v.get(0);
    }

    @Override
    public void replaceRoles(String tenant, Set<String> roles) {
        String key = tenant.trim();
        long now = System.currentTimeMillis();
        // 首次绑定即建策略行（版本 0）；已存在则版本 +1。
        if (db.writeReplaceRoles1(now, key) == 0) {
            db.writeReplaceRoles2(key, now);
        }
        rebuildRoles(key, roles, now);
    }

    @Override
    public boolean replaceRolesIfVersion(String tenant, Set<String> roles, long expectedVersion) {
        String key = tenant.trim();
        long now = System.currentTimeMillis();
        if (expectedVersion < 0) {
            // 期望尚无策略行：仅当确无行才建（唯一键并发保护）。
            try {
                db.writeReplaceRolesIfVersion1(key, now);
            } catch (DuplicateKeyException e) {
                return false;
            }
            rebuildRoles(key, roles, now);
            return true;
        }
        if (db.writeReplaceRolesIfVersion2(now, key, expectedVersion) == 0) {
            return false;
        }
        rebuildRoles(key, roles, now);
        return true;
    }

    @Override
    public void clear(String tenant) {
        if (tenant != null && !tenant.isBlank()) {
            String key = tenant.trim();
            db.writeClear1(key);
            db.writeClear2(key);
        }
    }

    @Override
    public List<String> listPolicyTenants() {
        return db.readListPolicyTenants1();
    }

    @Override
    public List<String> tenantsUsingRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return List.of();
        }
        return db.readTenantsUsingRole1(roleName.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** 全量重建某租户的角色绑定（先删后插，在 mutation executor 事务内与策略行同批提交）。 */
    private void rebuildRoles(String tenant, Set<String> roles, long now) {
        db.writeRebuildRoles1(tenant);
        for (String r : UserAccount.normalize(roles)) {
            try {
                db.writeRebuildRoles2(tenant, r, now);
            } catch (DuplicateKeyException ignored) {
                // 幂等：同批不会重复，防御性忽略。
            }
        }
    }
}
