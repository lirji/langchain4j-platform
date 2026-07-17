package com.lrj.platform.auth;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RoleServiceTest：验证 {@link RoleService} 的角色 scope 展开与有效权限合成。覆盖 admin 角色展开出平台级 scopes、
 * 直配 scopes 与角色 scopes 取并集、未知角色被忽略不抛异常，以及无角色时回退到直配 scopes。
 */
class RoleServiceTest {

    private final RoleService svc = new RoleService(new InMemoryRoleStore());

    @Test
    void expand_admin_containsNewPlatformScopes() {
        Set<String> scopes = svc.expand(Set.of("admin"));
        assertThat(scopes).contains("role-admin", "public-ingest", "chat", "ingest");
    }

    @Test
    void effectiveScopes_unionsRolesAndDirectScopes() {
        // 直配 {chat} + editor 角色({chat,ingest}) → {chat, ingest}
        UserAccount u = new UserAccount("u", "h", "acme", "u", Set.of("chat"), Set.of("editor"), true);
        assertThat(svc.effectiveScopes(u)).containsExactlyInAnyOrder("chat", "ingest");
    }

    @Test
    void unknownRole_isIgnored_noThrow() {
        assertThat(svc.expand(Set.of("does-not-exist"))).isEmpty();
    }

    @Test
    void noRoles_fallsBackToDirectScopes() {
        UserAccount u = new UserAccount("u", "h", "acme", "u", Set.of("chat", "analytics"), true);
        assertThat(svc.effectiveScopes(u)).containsExactlyInAnyOrder("chat", "analytics");
    }
}
