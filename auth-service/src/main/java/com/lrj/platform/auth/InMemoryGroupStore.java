package com.lrj.platform.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 内存用户组存储（默认）：无种子（空 == 与"未引入继承"等价），重启即清空。语义镜像 {@link InMemoryRoleStore}。 */
@Component
@ConditionalOnProperty(name = "app.auth.store", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryGroupStore implements GroupStore {

    private final ConcurrentMap<String, Group> byName = new ConcurrentHashMap<>();
    /** 乐观锁版本号（与 {@link #byName} 同键）。 */
    private final ConcurrentMap<String, Long> versions = new ConcurrentHashMap<>();

    @Override
    public Optional<Group> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byName.get(key(name)));
    }

    @Override
    public List<Group> findAll() {
        return byName.values().stream().sorted(Comparator.comparing(Group::name)).toList();
    }

    @Override
    public List<Group> findByNames(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        return names.stream()
                .filter(n -> n != null && !n.isBlank())
                .map(n -> byName.get(key(n)))
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public boolean createIfAbsent(Group group) {
        boolean created = byName.putIfAbsent(key(group.name()), group) == null;
        if (created) {
            versions.put(key(group.name()), 0L);
        }
        return created;
    }

    @Override
    public long versionOf(String name) {
        String k = key(name);
        return byName.containsKey(k) ? versions.getOrDefault(k, 0L) : -1L;
    }

    @Override
    public boolean updateIfVersion(Group group, long expectedVersion) {
        boolean[] ok = {false};
        byName.computeIfPresent(key(group.name()), (k, cur) -> {
            if (versions.getOrDefault(k, 0L) != expectedVersion) {
                return cur;
            }
            ok[0] = true;
            versions.merge(k, 1L, Long::sum);
            return group;
        });
        return ok[0];
    }

    @Override
    public boolean touchVersionIfVersion(String name, long expectedVersion) {
        boolean[] ok = {false};
        byName.computeIfPresent(key(name), (k, cur) -> {
            if (versions.getOrDefault(k, 0L) != expectedVersion) {
                return cur;
            }
            ok[0] = true;
            versions.merge(k, 1L, Long::sum);
            return cur;   // 只推进版本，组内容不变
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

    @Override
    public List<String> groupsUsingRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return List.of();
        }
        String r = roleName.trim().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        byName.values().forEach(g -> {
            if (g.roles().contains(r)) {
                out.add(g.name());
            }
        });
        out.sort(String::compareTo);
        return out;
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
