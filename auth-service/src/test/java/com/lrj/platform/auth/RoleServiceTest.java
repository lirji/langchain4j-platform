package com.lrj.platform.auth;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
