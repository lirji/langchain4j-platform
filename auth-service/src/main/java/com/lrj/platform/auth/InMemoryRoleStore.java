package com.lrj.platform.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 内存角色存储（默认）：种子角色字典，重启即回到种子态。本地/单测零外部依赖。 */
@Component
@ConditionalOnProperty(name = "app.auth.store", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryRoleStore implements RoleStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRoleStore.class);

    private final ConcurrentMap<String, Role> byName = new ConcurrentHashMap<>();
    /** 乐观锁版本号（与 {@link #byName} 同键）；变更在对应 key 的 computeIfPresent 内原子推进。 */
    private final ConcurrentMap<String, Long> versions = new ConcurrentHashMap<>();

    public InMemoryRoleStore() {
        for (Role r : SeedRoles.defaults()) {
            String k = key(r.name());
            byName.put(k, r);
            versions.put(k, 0L);
        }
        log.info("in-memory role store seeded with {} roles", byName.size());
    }

    @Override
    public Optional<Role> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byName.get(key(name)));
    }

    @Override
    public List<Role> findByNames(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        return names.stream()
                .filter(n -> n != null && !n.isBlank())
                .map(n -> byName.get(key(n)))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Override
    public List<Role> findAll() {
        return byName.values().stream()
                .sorted(Comparator.comparing(Role::name))
                .toList();
    }

    @Override
    public void save(Role role) {
        String k = key(role.name());
        byName.put(k, role);
        versions.putIfAbsent(k, 0L);
    }

    @Override
    public boolean createIfAbsent(Role role) {
        boolean created = byName.putIfAbsent(key(role.name()), role) == null;
        if (created) {
            versions.put(key(role.name()), 0L);
        }
        return created;
    }

    @Override
    public boolean update(Role role) {
        Role updated = byName.computeIfPresent(key(role.name()), (k, cur) -> {
            versions.merge(k, 1L, Long::sum);
            return role;
        });
        return updated != null;
    }

    @Override
    public long versionOf(String name) {
        String k = key(name);
        return byName.containsKey(k) ? versions.getOrDefault(k, 0L) : -1L;
    }

    @Override
    public boolean updateIfVersion(Role role, long expectedVersion) {
        boolean[] ok = {false};
        byName.computeIfPresent(key(role.name()), (k, cur) -> {
            if (versions.getOrDefault(k, 0L) != expectedVersion) {
                return cur;   // 版本不匹配：保持原值
            }
            ok[0] = true;
            versions.merge(k, 1L, Long::sum);
            return role;
        });
        return ok[0];
    }

    @Override
    public void delete(String name) {
        if (name != null) {
            byName.remove(key(name));
            versions.remove(key(name));
        }
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
