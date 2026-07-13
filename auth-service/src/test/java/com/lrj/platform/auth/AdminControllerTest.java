package com.lrj.platform.auth;

import com.lrj.platform.auth.dto.AdminDtos.CreateRoleRequest;
import com.lrj.platform.auth.dto.AdminDtos.CreateUserRequest;
import com.lrj.platform.auth.dto.AdminDtos.ReplaceRolesRequest;
import com.lrj.platform.auth.dto.AdminDtos.RoleView;
import com.lrj.platform.auth.dto.AdminDtos.UpdateRoleRequest;
import com.lrj.platform.auth.dto.AdminDtos.UpdateUserRequest;
import com.lrj.platform.auth.dto.AdminDtos.UserAdminView;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminControllerTest {

    private final PasswordHasher hasher = new PasswordHasher();
    private final AuthProperties props = new AuthProperties();
    private final InMemoryUserAccountStore userStore = new InMemoryUserAccountStore(hasher, props);
    private final InMemoryRoleStore roleStore = new InMemoryRoleStore();
    private final InMemoryRefreshSessionStore sessionStore = new InMemoryRefreshSessionStore();
    private final AdminService adminService = new AdminService(userStore, roleStore, new RoleService(roleStore),
            hasher, new PasswordPolicy(props), sessionStore, new InMemoryRbacMutationExecutor());
    private final AdminController controller = new AdminController(adminService, props);

    AdminControllerTest() {
        props.getRbac().setEnabled(true);
        props.getRbac().setAdminWritesEnabled(true);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void asRoleAdmin() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat", "role-admin")));
    }

    private static CreateUserRequest newUser(String name, String tenant, Set<String> roles) {
        return new CreateUserRequest(name, "secret1", tenant, roles, Set.of(), true);
    }

    @Test
    void withoutRoleAdminScope_isForbidden() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat", "ingest")));
        assertThatThrownBy(() -> controller.listUsers(0, 50))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(403));
        assertThatThrownBy(() -> controller.createUser(newUser("x", "acme", Set.of("viewer"))))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(403));
    }

    @Test
    void createUser_thenReplaceRoles_reflectsInStore() {
        asRoleAdmin();
        ResponseEntity<UserAdminView> created = controller.createUser(newUser("dave", "globex", Set.of("viewer")));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody().effectiveScopes()).contains("chat");   // viewer→chat
        assertThat(userStore.findByUsername("dave")).isPresent();

        UserAdminView after = controller.replaceRoles("dave", new ReplaceRolesRequest(Set.of("editor")), "0");
        assertThat(after.roles()).containsExactly("editor");
        assertThat(after.effectiveScopes()).contains("chat", "ingest");
        assertThat(userStore.findByUsername("dave").orElseThrow().roles()).containsExactly("editor");
    }

    @Test
    void createUser_withUnknownRole_isRejected() {
        asRoleAdmin();
        assertThatThrownBy(() -> controller.createUser(newUser("eve", "acme", Set.of("wizard"))))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    void createUser_intoReservedPublicTenant_isRejected() {
        asRoleAdmin();
        assertThatThrownBy(() -> controller.createUser(newUser("frank", "__public__", Set.of("viewer"))))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    void listUsers_returnsSeededAccounts_withTotalCount() {
        asRoleAdmin();
        ResponseEntity<List<UserAdminView>> resp = controller.listUsers(0, 50);
        assertThat(resp.getBody()).extracting(UserAdminView::username).contains("alice", "bob", "analyst-a");
        assertThat(resp.getHeaders().getFirst("X-Total-Count")).isEqualTo("3");
    }

    @Test
    void getUser_notFound_returns404() {
        asRoleAdmin();
        assertThatThrownBy(() -> controller.getUser("nobody"))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(404));
    }

    @Test
    void patchUser_updatesOnlyProvidedFields_keepsRoles() {
        asRoleAdmin();
        controller.createUser(new CreateUserRequest("gwen", "secret1", "acme",
                Set.of("viewer"), Set.of("chat"), true));
        UserAdminView after = controller.patchUser("gwen",
                new UpdateUserRequest("acme2", null, null, false), "0");   // 只改租户+禁用（gwen 版本 0）
        assertThat(after.tenant()).isEqualTo("acme2");
        assertThat(after.enabled()).isFalse();
        assertThat(after.roles()).containsExactly("viewer");          // 角色不变
    }

    @Test
    void deleteUser_returns204_andIsIdempotent() {
        asRoleAdmin();
        controller.createUser(newUser("harry", "acme", Set.of("viewer")));
        assertThat(controller.deleteUser("harry", "0").getStatusCode().value()).isEqualTo(204);
        assertThat(controller.deleteUser("harry", "0").getStatusCode().value()).isEqualTo(204);  // 幂等（已不存在，不校验版本）
        assertThat(userStore.findByUsername("harry")).isEmpty();
    }

    @Test
    void adminWrites_disabled_returns503_butReadsStillWork() {
        props.getRbac().setAdminWritesEnabled(false);
        asRoleAdmin();
        assertThat(controller.listUsers(0, 50).getStatusCode().value()).isEqualTo(200);  // 读放行
        assertThatThrownBy(() -> controller.createUser(newUser("x", "acme", Set.of("viewer"))))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(503));
    }

    @Test
    void roleCrud_create201_get_update_deleteReferenced409_deleteUnreferenced204() {
        asRoleAdmin();
        ResponseEntity<RoleView> created = controller.createRole(
                new CreateRoleRequest("support", Set.of("chat"), "客服"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(controller.getRole("support").scopes()).containsExactly("chat");

        RoleView updated = controller.updateRole("support", new UpdateRoleRequest(Set.of("chat", "ingest"), "客服+入库"), "0");
        assertThat(updated.scopes()).containsExactlyInAnyOrder("chat", "ingest");
        assertThat(updated.version()).isEqualTo(1L);

        // viewer 被 bob 引用 → 409（viewer 版本 0）
        assertThatThrownBy(() -> controller.deleteRole("viewer", "0"))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(409));
        // support 无人引用、版本 1 → 204
        assertThat(controller.deleteRole("support", "1").getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void createRole_duplicate_returns409() {
        asRoleAdmin();
        assertThatThrownBy(() -> controller.createRole(new CreateRoleRequest("viewer", Set.of("chat"), "dup")))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(409));
    }

    // ---- 乐观锁（If-Match 版本）----

    @Test
    void patchUser_withMatchingIfMatch_succeeds_andBumpsVersion() {
        asRoleAdmin();
        controller.createUser(newUser("ivy", "acme", Set.of("viewer")));
        UserAdminView before = controller.getUser("ivy");
        assertThat(before.version()).isEqualTo(0L);

        UserAdminView after = controller.patchUser("ivy",
                new UpdateUserRequest("acme2", null, null, null), String.valueOf(before.version()));
        assertThat(after.tenant()).isEqualTo("acme2");
        assertThat(after.version()).isEqualTo(1L);   // 版本前进
    }

    @Test
    void patchUser_withStaleIfMatch_returns412() {
        asRoleAdmin();
        controller.createUser(newUser("jack", "acme", Set.of("viewer")));
        // 先成功改一次（版本 0→1），再用陈旧版本 0 回写 → 前置条件失败 412
        controller.patchUser("jack", new UpdateUserRequest("acme2", null, null, null), "0");
        assertThatThrownBy(() -> controller.patchUser("jack",
                new UpdateUserRequest("acme3", null, null, null), "0"))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(412));
    }

    @Test
    void updateRole_withStaleIfMatch_returns412() {
        asRoleAdmin();
        controller.createRole(new CreateRoleRequest("support", Set.of("chat"), "客服"));
        controller.updateRole("support", new UpdateRoleRequest(Set.of("chat", "ingest"), "v1"), "0");   // 0→1
        assertThatThrownBy(() -> controller.updateRole("support",
                new UpdateRoleRequest(Set.of("chat"), "stale"), "0"))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(412));
    }

    @Test
    void writesWithoutIfMatch_return428() {
        asRoleAdmin();
        controller.createUser(newUser("kim", "acme", Set.of("viewer")));
        // 缺 If-Match：PATCH / PUT roles / DELETE 一律 428 precondition_required
        assertThatThrownBy(() -> controller.patchUser("kim", new UpdateUserRequest("acme2", null, null, null), null))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(428));
        assertThatThrownBy(() -> controller.replaceRoles("kim", new ReplaceRolesRequest(Set.of("editor")), null))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(428));
        assertThatThrownBy(() -> controller.deleteUser("kim", null))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(428));
    }

    @Test
    void deleteUser_withStaleIfMatch_returns412() {
        asRoleAdmin();
        controller.createUser(newUser("liam", "acme", Set.of("viewer")));
        controller.patchUser("liam", new UpdateUserRequest("acme2", null, null, null), "0"); // 0→1
        assertThatThrownBy(() -> controller.deleteUser("liam", "0")) // 陈旧版本删除 → 412
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(412));
        assertThat(userStore.findByUsername("liam")).isPresent(); // 未被删
    }

    @Test
    void badIfMatch_returns400() {
        asRoleAdmin();
        controller.createUser(newUser("kim", "acme", Set.of("viewer")));
        assertThatThrownBy(() -> controller.patchUser("kim",
                new UpdateUserRequest("acme2", null, null, null), "not-a-number"))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(400));
    }
}
