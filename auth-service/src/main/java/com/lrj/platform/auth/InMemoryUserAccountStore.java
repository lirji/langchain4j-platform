package com.lrj.platform.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 内存账号存储（默认）：种子演示账号，重启即失效。本地/单测零外部依赖。 */
@Component
@ConditionalOnProperty(name = "app.auth.store", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryUserAccountStore implements UserAccountStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryUserAccountStore.class);

    private final ConcurrentMap<String, UserAccount> byUsername = new ConcurrentHashMap<>();

    public InMemoryUserAccountStore(PasswordHasher hasher, AuthProperties props) {
        for (UserAccount u : SeedUsers.defaults(hasher, props.getDemoPassword())) {
            byUsername.put(key(u.username()), u);
        }
        log.info("in-memory user store seeded with {} demo accounts (login=手输 api-key 的平滑替换)", byUsername.size());
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byUsername.get(key(username)));
    }

    private static String key(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }
}
