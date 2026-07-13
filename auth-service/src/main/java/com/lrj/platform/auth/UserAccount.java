package com.lrj.platform.auth;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * 用户账号。{@code userId}/{@code tenant}/{@code scopes} 映射到平台既有的租户身份模型
 * （会话 JWT 的 sub=tenant / uid=userId / scopes），登录成功后原样带进 {@link
 * com.lrj.platform.security.TenantContext.Tenant}，下游授权仍只看 scope。
 *
 * <p>RBAC：{@code roles} 是角色名集合；登录签发令牌那一刻由 {@code RoleService} 展开成 scopes，
 * 再与本记录里的直配 {@code scopes} 取并集作为有效 scopes。直配 scopes（direct scopes）保留，用于
 * 迁移兜底与无角色场景（既有种子账号即如此）。下游服务对角色无感知——只认展开后的 scope。
 *
 * <p>{@code scopes}/{@code roles} 在紧凑构造器里归一：去空白、去重、按字典序排序、包成不可变集合，
 * 保证同一份权限在不同来源（内存/CSV/关系表）下有确定且稳定的表示。{@code username} 仅 trim。
 */
public record UserAccount(String username,
                          String passwordHash,
                          String tenant,
                          String userId,
                          Set<String> scopes,
                          Set<String> roles,
                          boolean enabled) {

    public UserAccount {
        username = username == null ? null : username.trim();
        scopes = normalize(scopes);
        roles = normalize(roles);
    }

    /** 向后兼容：无角色的 6 参构造（既有调用点与测试保持不变）。 */
    public UserAccount(String username, String passwordHash, String tenant, String userId,
                       Set<String> scopes, boolean enabled) {
        this(username, passwordHash, tenant, userId, scopes, Set.of(), enabled);
    }

    /** trim、去空、去重、按字典序排序，返回不可变快照（避免 Set.copyOf 的不确定迭代顺序）。 */
    static Set<String> normalize(Set<String> in) {
        if (in == null || in.isEmpty()) {
            return Set.of();
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String s : in) {
            if (s != null) {
                String t = s.trim();
                if (!t.isEmpty()) {
                    sorted.add(t);
                }
            }
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }
}
