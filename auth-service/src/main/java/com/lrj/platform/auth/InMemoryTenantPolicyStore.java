package com.lrj.platform.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存租户基础角色存储（默认）：无种子（空 == 与"未引入继承"等价），重启即清空。本地/单测零外部依赖。
 * 变更在对应 key 的 {@code compute} 内原子推进版本，语义对齐 {@link InMemoryRoleStore}。
 */
@Component
@ConditionalOnProperty(name = "app.auth.store", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryTenantPolicyStore implements TenantPolicyStore {

    private final ConcurrentMap<String, Set<String>> byTenant = new ConcurrentHashMap<>();
    /** 乐观锁版本号（与 {@link #byTenant} 同键）。 */
    private final ConcurrentMap<String, Long> versions = new ConcurrentHashMap<>();

    @Override
    public Set<String> rolesOf(String tenant) {
        if (tenant == null || tenant.isBlank()) {
            return Set.of();
        }
        return byTenant.getOrDefault(key(tenant), Set.of());
    }

    @Override
    public long versionOf(String tenant) {
        String k = key(tenant);
        return byTenant.containsKey(k) ? versions.getOrDefault(k, 0L) : -1L;
    }

    @Override
    public void replaceRoles(String tenant, Set<String> roles) {
        String k = key(tenant);
        // 首次绑定即"建行"，版本 0；之后每次替换 +1（对齐 create=0 语义）。写经全局锁串行，非原子读改写安全。
        if (byTenant.containsKey(k)) {
            versions.merge(k, 1L, Long::sum);
        } else {
            versions.put(k, 0L);
        }
        byTenant.put(k, UserAccount.normalize(roles));
    }

    @Override
    public boolean replaceRolesIfVersion(String tenant, Set<String> roles, long expectedVersion) {
        String k = key(tenant);
        boolean[] ok = {false};
        if (expectedVersion < 0) {
            // 期望尚无策略行：仅当当前确无行才建（并发下 putIfAbsent 保证只有一个成功）。
            Set<String> prev = byTenant.putIfAbsent(k, UserAccount.normalize(roles));
            if (prev == null) {
                versions.put(k, 0L);
                return true;
            }
            return false;
        }
        byTenant.computeIfPresent(k, (kk, cur) -> {
            if (versions.getOrDefault(kk, 0L) != expectedVersion) {
                return cur;
            }
            ok[0] = true;
            versions.merge(kk, 1L, Long::sum);
            return UserAccount.normalize(roles);
        });
        return ok[0];
    }

    @Override
    public void clear(String tenant) {
        if (tenant != null) {
            String k = key(tenant);
            byTenant.remove(k);
            versions.remove(k);
        }
    }

    @Override
    public List<String> listPolicyTenants() {
        List<String> out = new ArrayList<>(byTenant.keySet());
        out.sort(String::compareTo);
        return out;
    }

    @Override
    public List<String> tenantsUsingRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return List.of();
        }
        String r = roleName.trim().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        byTenant.forEach((t, roles) -> {
            if (roles.contains(r)) {
                out.add(t);
            }
        });
        out.sort(String::compareTo);
        return out;
    }

    private static String key(String tenant) {
        return tenant.trim();
    }
}
