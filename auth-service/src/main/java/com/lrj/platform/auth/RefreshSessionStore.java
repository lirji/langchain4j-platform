package com.lrj.platform.auth;

import java.util.Optional;

/** 刷新会话读写（含轮转）。接口 + 内存/JDBC 双实现。 */
public interface RefreshSessionStore {

    void create(RefreshSession session);

    Optional<RefreshSession> findByTokenHash(String tokenHash);

    void revoke(String tokenHash);

    /**
     * 撤销某用户的<b>全部</b>有效刷新会话（权限降低/禁用时用，尽快切断续期链）。默认抛
     * {@link UnsupportedOperationException}，由真实 store 覆盖。已签发的 access JWT 仍受 TTL 约束。
     */
    default void revokeByUsername(String username) {
        throw new UnsupportedOperationException("revokeByUsername not supported by this store");
    }

    /** 删除某用户的全部刷新会话（删号时用）。 */
    default void deleteByUsername(String username) {
        throw new UnsupportedOperationException("deleteByUsername not supported by this store");
    }
}
