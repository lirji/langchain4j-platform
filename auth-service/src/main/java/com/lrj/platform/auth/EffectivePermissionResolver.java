package com.lrj.platform.auth;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 继承式 RBAC 的<b>有效权限合成点</b>。取代 {@link RoleService#effectiveScopes} 成为 {@code AuthService}/
 * {@code AdminService} 计算有效 scopes 的唯一入口。角色仍是全局能力包（展开仍复用 {@link RoleService}/
 * {@code RoleStore}），本类只负责把三层继承并进来：
 *
 * <pre>有效 scopes = 个人直配 ∪ 个人角色展开 ∪ 租户基础角色展开 ∪ 用户各组角色展开</pre>
 *
 * <p>灰度：{@code app.auth.rbac.inheritanceEnabled=false}（默认）时只做前两项（= 加继承前的两级行为，逐字节等价）；
 * 开后再并入租户/组两层。因下游只认最终扁平 scope 集、对来源无感知，合成在这一处完成即全链路生效、JWT 形状不变、
 * 下游零改动。
 *
 * <p>登录热路径用 {@link #resolve} 批量查角色（避免 N+1）。{@link #effectiveScopesProspective} 供 {@code AdminService}
 * 的"最后管理员"前瞻评估：可覆盖某一维度（租户角色 / 组角色 / 角色 scope / 用户所属组）以模拟"假设本次变更已应用"。
 */
@Service
public class EffectivePermissionResolver {

    static final String ROLE_ADMIN_SCOPE = "role-admin";

    private final RoleService roleService;
    private final RoleStore roleStore;
    private final TenantPolicyStore tenantPolicyStore;
    private final GroupStore groupStore;
    private final UserGroupStore userGroupStore;
    private final AuthProperties props;

    public EffectivePermissionResolver(RoleService roleService, RoleStore roleStore,
                                       TenantPolicyStore tenantPolicyStore, GroupStore groupStore,
                                       UserGroupStore userGroupStore, AuthProperties props) {
        this.roleService = roleService;
        this.roleStore = roleStore;
        this.tenantPolicyStore = tenantPolicyStore;
        this.groupStore = groupStore;
        this.userGroupStore = userGroupStore;
        this.props = props;
    }

    /** 测试便捷构造：空租户/组存储 + 继承默认关闭 → 行为等价加继承前的两级 RBAC。 */
    static EffectivePermissionResolver twoLayer(RoleStore roleStore) {
        return new EffectivePermissionResolver(new RoleService(roleStore), roleStore,
                new InMemoryTenantPolicyStore(), new InMemoryGroupStore(),
                new InMemoryUserGroupStore(), new AuthProperties());
    }

    /** 委托角色存在性校验（写入前拒绝未知角色），使调用方只依赖本 resolver 一个 RBAC 门面。 */
    public void requireRolesExist(Set<String> roles) {
        roleService.requireRolesExist(roles);
    }

    /** 视图用：展开一组角色名为 scopes 并集（未知角色 fail-closed）。供租户/组的"有效 scopes"预览。 */
    public Set<String> expand(Set<String> roleNames) {
        return roleService.expand(roleNames);
    }

    private boolean inheritanceOn() {
        return props.getRbac().isInheritanceEnabled();
    }

    // ---- 归因结果（供 admin 视图与 /effective-permissions 端点）----

    /**
     * 有效权限的四层分解 + 并集 + 逐 scope 归因。{@code sources}：scope → 来源标签列表，标签形如
     * {@code direct} / {@code role:<name>} / {@code tenant:<roleName>} / {@code group:<groupName>:<roleName>}，
     * 供前端准确展示"这条权限从哪来"。
     */
    public record EffectivePermissions(Set<String> directScopes,
                                       Set<String> personalRoleScopes,
                                       Set<String> tenantScopes,
                                       Set<String> groupScopes,
                                       Set<String> all,
                                       Map<String, List<String>> sources) {}

    /** 账号有效 scopes（登录签发 / 视图预览用）。 */
    public Set<String> effectiveScopes(UserAccount user) {
        return resolve(user).all();
    }

    /** 三层合并 + 归因。批量查角色避免 N+1。inheritance 关时租户/组两层为空（退两级）。 */
    public EffectivePermissions resolve(UserAccount user) {
        Map<String, LinkedHashSet<String>> sources = new LinkedHashMap<>();

        Set<String> direct = new LinkedHashSet<>(user.scopes());
        for (String s : direct) {
            addSource(sources, s, "direct");
        }

        Set<String> tenantRoleNames = inheritanceOn() ? tenantPolicyStore.rolesOf(user.tenant()) : Set.of();
        Set<String> groupNames = inheritanceOn() ? userGroupStore.groupsOf(user.username()) : Set.of();
        List<Group> groups = inheritanceOn() ? groupStore.findByNames(groupNames) : List.of();

        // 一次批量把所有涉及的角色名解析为 scopes（未知角色缺席 → fail-closed，不授予 scope）。
        Set<String> allRoleNames = new LinkedHashSet<>(user.roles());
        allRoleNames.addAll(tenantRoleNames);
        for (Group g : groups) {
            allRoleNames.addAll(g.roles());
        }
        Map<String, Set<String>> roleScopes = new LinkedHashMap<>();
        for (Role r : roleStore.findByNames(allRoleNames)) {
            roleScopes.put(r.name(), r.scopes());
        }

        Set<String> personalRoleScopes = new LinkedHashSet<>();
        for (String rn : user.roles()) {
            for (String s : roleScopes.getOrDefault(rn, Set.of())) {
                personalRoleScopes.add(s);
                addSource(sources, s, "role:" + rn);
            }
        }
        Set<String> tenantScopes = new LinkedHashSet<>();
        for (String rn : tenantRoleNames) {
            for (String s : roleScopes.getOrDefault(rn, Set.of())) {
                tenantScopes.add(s);
                addSource(sources, s, "tenant:" + rn);
            }
        }
        Set<String> groupScopes = new LinkedHashSet<>();
        for (Group g : groups) {
            for (String rn : g.roles()) {
                for (String s : roleScopes.getOrDefault(rn, Set.of())) {
                    groupScopes.add(s);
                    addSource(sources, s, "group:" + g.name() + ":" + rn);
                }
            }
        }

        Set<String> all = new LinkedHashSet<>();
        all.addAll(direct);
        all.addAll(personalRoleScopes);
        all.addAll(tenantScopes);
        all.addAll(groupScopes);

        Map<String, List<String>> sourcesView = new TreeMap<>();
        for (String s : all) {
            sourcesView.put(s, new ArrayList<>(sources.getOrDefault(s, new LinkedHashSet<>())));
        }
        return new EffectivePermissions(
                unmod(direct), unmod(personalRoleScopes), unmod(tenantScopes), unmod(groupScopes),
                unmod(all), java.util.Collections.unmodifiableMap(sourcesView));
    }

    /**
     * 前瞻有效 scopes：供"最后管理员"守卫模拟"假设本次变更已应用"。各 lookup 传 null 用 store 默认；覆盖非 null
     * 时替换该维度（如 {@code tenantRolesFn} 对目标租户返回新绑定角色）。{@code groupNames} 为该用户在评估世界中的
     * 组集（null 用 store）。inheritance 关时租户/组两层不参与（与 token 现实一致）。
     */
    public Set<String> effectiveScopesProspective(UserAccount user, Set<String> groupNames,
                                                  Function<String, Set<String>> tenantRolesFn,
                                                  Function<String, Set<String>> groupRolesFn,
                                                  Function<String, Set<String>> roleScopesFn) {
        Function<String, Set<String>> tenantRoles = tenantRolesFn != null ? tenantRolesFn : tenantPolicyStore::rolesOf;
        Function<String, Set<String>> groupRoles = groupRolesFn != null ? groupRolesFn
                : g -> groupStore.findByName(g).map(Group::roles).orElse(Set.of());
        Function<String, Set<String>> roleScopes = roleScopesFn != null ? roleScopesFn
                : rn -> roleStore.findByName(rn).map(Role::scopes).orElse(Set.of());
        Set<String> gn = groupNames != null ? groupNames : userGroupStore.groupsOf(user.username());

        Set<String> roleNames = new LinkedHashSet<>(user.roles());
        if (inheritanceOn()) {
            roleNames.addAll(nz(tenantRoles.apply(user.tenant())));
            for (String g : gn) {
                roleNames.addAll(nz(groupRoles.apply(g)));
            }
        }
        Set<String> out = new LinkedHashSet<>(user.scopes());
        for (String rn : roleNames) {
            out.addAll(nz(roleScopes.apply(rn)));
        }
        return out;
    }

    private static void addSource(Map<String, LinkedHashSet<String>> sources, String scope, String source) {
        sources.computeIfAbsent(scope, k -> new LinkedHashSet<>()).add(source);
    }

    private static Set<String> unmod(Set<String> s) {
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(s));
    }

    private static Set<String> nz(Set<String> s) {
        return s == null ? Set.of() : s;
    }
}
