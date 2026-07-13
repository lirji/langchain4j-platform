package com.lrj.platform.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 内存账号存储（默认）：种子演示账号，重启即失效。本地/单测零外部依赖。 */
@Component
@ConditionalOnProperty(name = "app.auth.store", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryUserAccountStore implements UserAccountStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryUserAccountStore.class);

    private final ConcurrentMap<String, UserAccount> byUsername = new ConcurrentHashMap<>();
    /** 乐观锁版本号（与 {@link #byUsername} 同键）；所有变更均在对应 key 的 computeIfPresent 内原子推进。 */
    private final ConcurrentMap<String, Long> versions = new ConcurrentHashMap<>();

    public InMemoryUserAccountStore(PasswordHasher hasher, AuthProperties props) {
        if (props.getSeed().isEnabled()) {
            for (UserAccount u : SeedUsers.defaults(hasher, props.getDemoPassword())) {
                String k = key(u.username());
                byUsername.put(k, u);
                versions.put(k, 0L);
            }
            log.info("in-memory user store seeded with {} demo accounts (login=手输 api-key 的平滑替换)", byUsername.size());
        }
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byUsername.get(key(username)));
    }

    @Override
    public void save(UserAccount account) {
        String k = key(account.username());
        byUsername.put(k, account);
        versions.putIfAbsent(k, 0L);
    }

    @Override
    public void update(UserAccount account) {
        String k = key(account.username());
        byUsername.put(k, account);
        versions.merge(k, 1L, Long::sum);
    }

    @Override
    public boolean createIfAbsent(UserAccount account) {
        // putIfAbsent 原子占位：并发同名注册只有一个成功，不覆盖。
        boolean created = byUsername.putIfAbsent(key(account.username()), account) == null;
        if (created) {
            versions.put(key(account.username()), 0L);
        }
        return created;
    }

    @Override
    public boolean updateProfile(String username, String tenant, String passwordHash,
                                 Set<String> directScopes, boolean enabled) {
        UserAccount updated = byUsername.computeIfPresent(key(username), (k, cur) -> {
            versions.merge(k, 1L, Long::sum);
            return new UserAccount(cur.username(), passwordHash, tenant, cur.userId(),
                    directScopes, cur.roles(), enabled);
        });
        return updated != null;
    }

    @Override
    public boolean replaceRoles(String username, Set<String> roles) {
        UserAccount updated = byUsername.computeIfPresent(key(username), (k, cur) -> {
            versions.merge(k, 1L, Long::sum);
            return new UserAccount(cur.username(), cur.passwordHash(), cur.tenant(), cur.userId(),
                    cur.scopes(), roles, cur.enabled());
        });
        return updated != null;
    }

    @Override
    public long versionOf(String username) {
        String k = key(username);
        return byUsername.containsKey(k) ? versions.getOrDefault(k, 0L) : -1L;
    }

    @Override
    public boolean updateProfileIfVersion(String username, String tenant, String passwordHash,
                                          Set<String> directScopes, boolean enabled, long expectedVersion) {
        boolean[] ok = {false};
        byUsername.computeIfPresent(key(username), (k, cur) -> {
            if (versions.getOrDefault(k, 0L) != expectedVersion) {
                return cur;   // 版本不匹配：保持原值，ok 仍为 false
            }
            ok[0] = true;
            versions.merge(k, 1L, Long::sum);
            return new UserAccount(cur.username(), passwordHash, tenant, cur.userId(),
                    directScopes, cur.roles(), enabled);
        });
        return ok[0];
    }

    @Override
    public boolean replaceRolesIfVersion(String username, Set<String> roles, long expectedVersion) {
        boolean[] ok = {false};
        byUsername.computeIfPresent(key(username), (k, cur) -> {
            if (versions.getOrDefault(k, 0L) != expectedVersion) {
                return cur;
            }
            ok[0] = true;
            versions.merge(k, 1L, Long::sum);
            return new UserAccount(cur.username(), cur.passwordHash(), cur.tenant(), cur.userId(),
                    cur.scopes(), roles, cur.enabled());
        });
        return ok[0];
    }

    @Override
    public List<UserAccount> findByRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return List.of();
        }
        String r = roleName.trim().toLowerCase(Locale.ROOT);
        return byUsername.values().stream()
                .filter(u -> u.roles().contains(r))
                .sorted(Comparator.comparing(UserAccount::username))
                .toList();
    }

    @Override
    public List<UserAccount> findAll() {
        return byUsername.values().stream()
                .sorted(Comparator.comparing(UserAccount::username))
                .toList();
    }

    @Override
    public List<UserAccount> findPage(int offset, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return byUsername.values().stream()
                .sorted(Comparator.comparing(UserAccount::username))
                .skip(Math.max(0, offset))
                .limit(limit)
                .toList();
    }

    @Override
    public int count() {
        return byUsername.size();
    }

    @Override
    public void delete(String username) {
        if (username != null && !username.isBlank()) {
            byUsername.remove(key(username));
            versions.remove(key(username));
        }
    }

    private static String key(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }
}
