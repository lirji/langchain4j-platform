package com.lrj.platform.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 内存刷新会话存储（默认）。重启即失效，本地/单测零依赖。 */
@Component
@ConditionalOnProperty(name = "app.auth.store", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryRefreshSessionStore implements RefreshSessionStore {

    private final ConcurrentMap<String, RefreshSession> byHash = new ConcurrentHashMap<>();

    @Override
    public void create(RefreshSession session) {
        byHash.put(session.tokenHash(), session);
    }

    @Override
    public Optional<RefreshSession> findByTokenHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byHash.get(tokenHash));
    }

    @Override
    public void revoke(String tokenHash) {
        byHash.computeIfPresent(tokenHash, (h, s) ->
                new RefreshSession(s.tokenHash(), s.username(), s.createdAt(), s.expiresAt(), true));
    }

    @Override
    public void revokeByUsername(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        byHash.replaceAll((h, s) -> username.equals(s.username()) && !s.revoked()
                ? new RefreshSession(s.tokenHash(), s.username(), s.createdAt(), s.expiresAt(), true)
                : s);
    }

    @Override
    public void deleteByUsername(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        byHash.values().removeIf(s -> username.equals(s.username()));
    }
}
