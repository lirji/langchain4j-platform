package com.lrj.platform.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RBAC 核心：把角色展开成 scopes。这是 RBAC 唯一的"策略"逻辑——**只在签发令牌那一刻**由
 * {@code AuthService} 调用，展开结果签进会话 JWT，下游服务仍只认 scope、对角色无感知（零改动）。
 *
 * <p>有效 scopes = 各角色 scopes 并集 ∪ 账号直配 scopes（直配保留作迁移兜底与无角色场景）。
 * 是否展开角色由 {@code AuthService} 依 {@code app.auth.rbac.enabled} 决定（本类恒展开，供直接单测）。
 *
 * <p>未知角色<b>fail-closed</b>：不授予任何 scope（展开时静默忽略并告警）；写入路径
 * （{@link #requireRolesExist}）则直接拒绝，防止把不存在的角色写进用户/关系表。
 */
@Service
public class RoleService {

    private static final Logger log = LoggerFactory.getLogger(RoleService.class);

    private final RoleStore roleStore;

    public RoleService(RoleStore roleStore) {
        this.roleStore = roleStore;
    }

    /** 展开一组角色名为 scopes 并集。未知角色不授予 scope，仅告警（fail-closed）。 */
    public Set<String> expand(Set<String> roleNames) {
        Set<String> out = new LinkedHashSet<>();
        if (roleNames == null || roleNames.isEmpty()) {
            return out;
        }
        List<Role> found = roleStore.findByNames(roleNames);
        Set<String> foundNames = found.stream().map(Role::name).collect(Collectors.toSet());
        for (Role r : found) {
            out.addAll(r.scopes());
        }
        for (String requested : roleNames) {
            if (requested != null && !foundNames.contains(norm(requested))) {
                log.warn("unknown role ignored on expand (fail-closed, no scope granted): {}", norm(requested));
            }
        }
        return out;
    }

    /** 账号的有效 scopes：角色展开 ∪ 直配 scopes。 */
    public Set<String> effectiveScopes(UserAccount user) {
        Set<String> out = new LinkedHashSet<>(user.scopes());
        out.addAll(expand(user.roles()));
        return out;
    }

    /** 写入前校验：所有角色必须已存在，否则抛 400（禁止把未知角色写进用户/关系表）。 */
    public void requireRolesExist(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return;
        }
        Set<String> foundNames = roleStore.findByNames(roles).stream()
                .map(Role::name).collect(Collectors.toSet());
        for (String r : roles) {
            if (r == null || !foundNames.contains(norm(r))) {
                throw new AuthException(400, "unknown_role", "角色不存在: " + r);
            }
        }
    }

    private static String norm(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
