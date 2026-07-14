package com.lrj.platform.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 内存用户↔组成员存储（默认）：无种子，重启即清空。写经 {@code RbacMutationExecutor} 全局锁串行。 */
@Component
@ConditionalOnProperty(name = "app.auth.store", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryUserGroupStore implements UserGroupStore {

    /** username(小写) → 组名集（小写）。 */
    private final ConcurrentMap<String, Set<String>> byUser = new ConcurrentHashMap<>();

    @Override
    public Set<String> groupsOf(String username) {
        if (username == null || username.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(byUser.getOrDefault(userKey(username), Set.of()));
    }

    @Override
    public List<String> membersOf(String group) {
        if (group == null || group.isBlank()) {
            return List.of();
        }
        String g = groupKey(group);
        List<String> out = new ArrayList<>();
        byUser.forEach((u, groups) -> {
            if (groups.contains(g)) {
                out.add(u);
            }
        });
        out.sort(String::compareTo);
        return out;
    }

    @Override
    public void replaceGroupsForUser(String username, Set<String> groups) {
        String u = userKey(username);
        Set<String> norm = normalizeGroups(groups);
        if (norm.isEmpty()) {
            byUser.remove(u);
        } else {
            byUser.put(u, norm);
        }
    }

    @Override
    public void replaceMembersForGroup(String group, Set<String> members) {
        String g = groupKey(group);
        Set<String> newMembers = new LinkedHashSet<>();
        for (String m : members) {
            if (m != null && !m.isBlank()) {
                newMembers.add(userKey(m));
            }
        }
        // 先从所有当前成员移除该组，再给新成员加上（O(users)，控制面低频可接受）。
        byUser.forEach((u, groups) -> {
            if (groups.contains(g) && !newMembers.contains(u)) {
                Set<String> next = new LinkedHashSet<>(groups);
                next.remove(g);
                if (next.isEmpty()) {
                    byUser.remove(u);
                } else {
                    byUser.put(u, next);
                }
            }
        });
        for (String u : newMembers) {
            byUser.compute(u, (k, groups) -> {
                Set<String> next = groups == null ? new LinkedHashSet<>() : new LinkedHashSet<>(groups);
                next.add(g);
                return next;
            });
        }
    }

    @Override
    public void removeAllForUser(String username) {
        if (username != null) {
            byUser.remove(userKey(username));
        }
    }

    @Override
    public void removeAllForGroup(String group) {
        if (group == null || group.isBlank()) {
            return;
        }
        String g = groupKey(group);
        byUser.forEach((u, groups) -> {
            if (groups.contains(g)) {
                Set<String> next = new LinkedHashSet<>(groups);
                next.remove(g);
                if (next.isEmpty()) {
                    byUser.remove(u);
                } else {
                    byUser.put(u, next);
                }
            }
        });
    }

    private static Set<String> normalizeGroups(Set<String> groups) {
        Set<String> out = new LinkedHashSet<>();
        if (groups != null) {
            for (String g : groups) {
                if (g != null && !g.isBlank()) {
                    out.add(groupKey(g));
                }
            }
        }
        return out;
    }

    private static String userKey(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private static String groupKey(String group) {
        return group.trim().toLowerCase(Locale.ROOT);
    }
}
