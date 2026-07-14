package com.lrj.platform.auth;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 继承式 RBAC 合成：三层并集、fail-closed、空继承==两级等价、分层归因、灰度退两级、批量无 N+1。 */
class EffectivePermissionResolverTest {

    private final InMemoryRoleStore roles = new InMemoryRoleStore();
    private final InMemoryTenantPolicyStore tenants = new InMemoryTenantPolicyStore();
    private final InMemoryGroupStore groups = new InMemoryGroupStore();
    private final InMemoryUserGroupStore userGroups = new InMemoryUserGroupStore();

    private EffectivePermissionResolver resolver(boolean inheritance) {
        AuthProperties p = new AuthProperties();
        p.getRbac().setInheritanceEnabled(inheritance);
        return new EffectivePermissionResolver(new RoleService(roles), roles, tenants, groups, userGroups, p);
    }

    private static UserAccount user(String name, String tenant, Set<String> direct, Set<String> roleNames) {
        return new UserAccount(name, "h", tenant, name, direct, roleNames, true);
    }

    @Test
    void threeLayerUnion() {
        tenants.replaceRoles("acme", Set.of("viewer"));                 // 租户基础 → chat
        groups.createIfAbsent(new Group("eng", "工程组", Set.of("editor")));  // 组 → chat,ingest
        userGroups.replaceGroupsForUser("u", Set.of("eng"));
        UserAccount u = user("u", "acme", Set.of("voice"), Set.of("analyst"));  // 个人角色 → chat,analytics；直配 voice
        assertThat(resolver(true).effectiveScopes(u))
                .containsExactlyInAnyOrder("chat", "ingest", "analytics", "voice");
    }

    @Test
    void inheritanceOff_isTwoLayerEquivalent() {
        // 即便租户/组绑了 admin，继承关时也完全不生效，等价于 direct ∪ 个人角色。
        tenants.replaceRoles("acme", Set.of("admin"));
        groups.createIfAbsent(new Group("eng", "", Set.of("admin")));
        userGroups.replaceGroupsForUser("u", Set.of("eng"));
        UserAccount u = user("u", "acme", Set.of("chat"), Set.of("viewer"));
        assertThat(resolver(false).effectiveScopes(u)).containsExactly("chat");
    }

    @Test
    void unknownRolesFailClosed_grantNoScope() {
        tenants.replaceRoles("acme", Set.of("ghost"));
        groups.createIfAbsent(new Group("eng", "", Set.of("phantom")));
        userGroups.replaceGroupsForUser("u", Set.of("eng"));
        UserAccount u = user("u", "acme", Set.of(), Set.of("nope"));
        assertThat(resolver(true).effectiveScopes(u)).isEmpty();
    }

    @Test
    void resolveAttributesEachScopeToItsSources() {
        tenants.replaceRoles("acme", Set.of("viewer"));                 // chat via tenant
        groups.createIfAbsent(new Group("eng", "", Set.of("editor")));  // chat,ingest via group
        userGroups.replaceGroupsForUser("u", Set.of("eng"));
        UserAccount u = user("u", "acme", Set.of("voice"), Set.of("analyst"));  // analytics via role, voice direct

        EffectivePermissionResolver.EffectivePermissions ep = resolver(true).resolve(u);
        assertThat(ep.directScopes()).containsExactly("voice");
        assertThat(ep.personalRoleScopes()).containsExactlyInAnyOrder("chat", "analytics");
        assertThat(ep.tenantScopes()).containsExactly("chat");
        assertThat(ep.groupScopes()).containsExactlyInAnyOrder("chat", "ingest");
        assertThat(ep.all()).containsExactlyInAnyOrder("chat", "ingest", "analytics", "voice");
        // chat 三处来源；voice 仅直配；ingest 仅组
        assertThat(ep.sources().get("chat")).contains("role:analyst", "tenant:viewer", "group:eng:editor");
        assertThat(ep.sources().get("voice")).containsExactly("direct");
        assertThat(ep.sources().get("ingest")).containsExactly("group:eng:editor");
    }

    @Test
    void batchResolvesRoles_noNPlusOne() {
        RoleStore mockRoles = mock(RoleStore.class);
        when(mockRoles.findByNames(anyCollection()))
                .thenReturn(List.of(new Role("editor", Set.of("chat", "ingest"), "")));
        AuthProperties p = new AuthProperties();
        p.getRbac().setInheritanceEnabled(true);
        EffectivePermissionResolver r = new EffectivePermissionResolver(
                new RoleService(mockRoles), mockRoles, tenants, groups, userGroups, p);

        r.effectiveScopes(user("u", "acme", Set.of(), Set.of("editor")));

        verify(mockRoles, times(1)).findByNames(anyCollection());  // 一次批量
        verify(mockRoles, never()).findByName(anyString());        // 不逐个查
    }
}
