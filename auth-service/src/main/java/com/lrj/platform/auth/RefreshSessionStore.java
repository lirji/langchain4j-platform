package com.lrj.platform.auth;

import java.util.Optional;

/** 刷新会话读写（含轮转）。接口 + 内存/JDBC 双实现。 */
public interface RefreshSessionStore {

    void create(RefreshSession session);

    Optional<RefreshSession> findByTokenHash(String tokenHash);

    void revoke(String tokenHash);
}
