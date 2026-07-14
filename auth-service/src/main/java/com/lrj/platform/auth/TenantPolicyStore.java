package com.lrj.platform.auth;

import java.util.List;
import java.util.Set;

/**
 * 租户基础角色（继承式 RBAC 的最外层：租户级绑定，全体成员继承）。租户当前不是一等实体——它只是
 * {@link UserAccount#tenant()} 上的一个字符串，隐式存在（建户/注册时落成 USERS.TENANT）。因此这里做的是
 * <b>策略覆盖</b>而非实体：仅承载"租户 → 一组全局角色名"的绑定 + 一个乐观锁版本，不建 TENANTS 表。
 *
 * <p>无绑定行 == 空并集 == 与"未引入继承"逐字节等价（灰度前提）。接口 + 内存/JDBC 双实现（沿用项目
 * "接口 + @ConditionalOnProperty(app.auth.store) 切换"主导写法）；写经 {@code RbacMutationExecutor} 原子执行。
 * 引用完整性（角色须存在）由服务层（{@code AdminService}）保证，不建外键。
 */
public interface TenantPolicyStore {

    /** 租户的基础角色名集合（登录展开继承用）；无绑定返回空集。 */
    Set<String> rolesOf(String tenant);

    /**
     * 当前乐观锁版本号；无策略行返回 {@code -1}（语义同 {@link UserAccountStore#versionOf}）。租户无实体行，
     * 版本挂在惰性 upsert 的策略行上——首次绑定角色时建行、版本 0，之后每次替换 +1。
     */
    long versionOf(String tenant);

    /** 全量替换租户基础角色（幂等）。无策略行则惰性建行。 */
    void replaceRoles(String tenant, Set<String> roles);

    /**
     * 条件全量替换（乐观锁）：仅当当前版本 == {@code expectedVersion} 才替换并版本 +1；否则返回 false（409）。
     * {@code expectedVersion == -1} 表示"期望尚无策略行"，用于首次绑定的并发保护。
     */
    boolean replaceRolesIfVersion(String tenant, Set<String> roles, long expectedVersion);

    /** 清空某租户的基础角色（删策略行）。幂等。 */
    void clear(String tenant);

    /** 列出所有<b>已配置策略</b>的租户（可能少于实际用到的租户；租户全集由 {@code AdminService} 并上用户表 distinct）。 */
    List<String> listPolicyTenants();

    /** 反查引用了某角色的租户（角色删除的引用完整性检查用）。 */
    List<String> tenantsUsingRole(String roleName);
}
