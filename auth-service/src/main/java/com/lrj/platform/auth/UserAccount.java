package com.lrj.platform.auth;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 用户账号。{@code userId}/{@code tenant}/{@code scopes} 映射到平台既有的租户身份模型
 * （会话 JWT 的 sub=tenant / uid=userId / scopes），登录成功后原样带进 {@link
 * com.lrj.platform.security.TenantContext.Tenant}，下游授权仍只看 scope。
 */
public record UserAccount(String username,
                          String passwordHash,
                          String tenant,
                          String userId,
                          Set<String> scopes,
                          boolean enabled) {

    public UserAccount {
        scopes = scopes == null ? Set.of() : new LinkedHashSet<>(scopes);
    }
}
