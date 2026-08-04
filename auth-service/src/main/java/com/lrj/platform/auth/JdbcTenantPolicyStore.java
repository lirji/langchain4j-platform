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

    private final JdbcTemplate jdbc;

    public JdbcTenantPolicyStore(DataSource authDataSource) {
        this.jdbc = new JdbcTemplate(authDataSource);
        init();
    }

    private void init() {
        jdbc.queryForList("SELECT TENANT, VERSION, UPDATED_AT FROM TENANT_POLICY WHERE 1=0");
        jdbc.queryForList("SELECT TENANT, ROLE_NAME, CREATED_AT FROM TENANT_ROLE WHERE 1=0");
        log.info("TENANT_POLICY/TENANT_ROLE schema verified");
    }

    @Override
    public Set<String> rolesOf(String tenant) {
        if (tenant == null || tenant.isBlank()) {
            return Set.of();
        }
        List<String> roles = jdbc.queryForList(
                "SELECT ROLE_NAME FROM TENANT_ROLE WHERE TENANT=? ORDER BY ROLE_NAME", String.class, tenant.trim());
        return new LinkedHashSet<>(roles);
    }

    @Override
    public long versionOf(String tenant) {
        List<Long> v = jdbc.queryForList("SELECT VERSION FROM TENANT_POLICY WHERE TENANT=?", Long.class, tenant.trim());
        return v.isEmpty() ? -1L : v.get(0);
    }

    @Override
    public void replaceRoles(String tenant, Set<String> roles) {
        String key = tenant.trim();
        long now = System.currentTimeMillis();
        // 首次绑定即建策略行（版本 0）；已存在则版本 +1。
        if (jdbc.update("UPDATE TENANT_POLICY SET VERSION=VERSION+1, UPDATED_AT=? WHERE TENANT=?", now, key) == 0) {
            jdbc.update("INSERT INTO TENANT_POLICY (TENANT, VERSION, UPDATED_AT) VALUES (?, 0, ?)", key, now);
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
                jdbc.update("INSERT INTO TENANT_POLICY (TENANT, VERSION, UPDATED_AT) VALUES (?, 0, ?)", key, now);
            } catch (DuplicateKeyException e) {
                return false;
            }
            rebuildRoles(key, roles, now);
            return true;
        }
        if (jdbc.update("UPDATE TENANT_POLICY SET VERSION=VERSION+1, UPDATED_AT=? WHERE TENANT=? AND VERSION=?",
                now, key, expectedVersion) == 0) {
            return false;
        }
        rebuildRoles(key, roles, now);
        return true;
    }

    @Override
    public void clear(String tenant) {
        if (tenant != null && !tenant.isBlank()) {
            String key = tenant.trim();
            jdbc.update("DELETE FROM TENANT_ROLE WHERE TENANT=?", key);
            jdbc.update("DELETE FROM TENANT_POLICY WHERE TENANT=?", key);
        }
    }

    @Override
    public List<String> listPolicyTenants() {
        return jdbc.queryForList("SELECT TENANT FROM TENANT_POLICY ORDER BY TENANT", String.class);
    }

    @Override
    public List<String> tenantsUsingRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return List.of();
        }
        return jdbc.queryForList("SELECT DISTINCT TENANT FROM TENANT_ROLE WHERE ROLE_NAME=? ORDER BY TENANT",
                String.class, roleName.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** 全量重建某租户的角色绑定（先删后插，在 mutation executor 事务内与策略行同批提交）。 */
    private void rebuildRoles(String tenant, Set<String> roles, long now) {
        jdbc.update("DELETE FROM TENANT_ROLE WHERE TENANT=?", tenant);
        for (String r : UserAccount.normalize(roles)) {
            try {
                jdbc.update("INSERT INTO TENANT_ROLE (TENANT, ROLE_NAME, CREATED_AT) VALUES (?, ?, ?)", tenant, r, now);
            } catch (DuplicateKeyException ignored) {
                // 幂等：同批不会重复，防御性忽略。
            }
        }
    }
}
