package com.lrj.platform.auth;

import java.time.Instant;

/**
 * 刷新会话行。只存刷新令牌的 <b>SHA-256 哈希</b>（原串仅在 httpOnly cookie 里，服务端不留明文）。
 * 刷新时按用户名回查最新账号（能感知禁用/scope 变更），故本行不冗余 tenant/scopes。
 */
public record RefreshSession(String tokenHash,
                             String username,
                             Instant createdAt,
                             Instant expiresAt,
                             boolean revoked) {

    boolean isActive(Instant now) {
        return !revoked && expiresAt != null && now.isBefore(expiresAt);
    }
}
