package com.lrj.platform.auth;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 用户组读写（{@link Group} = 命名的成员容器 + 一组全局角色绑定）。接口 + 内存/JDBC 双实现，语义精确镜像
 * {@link RoleStore}（组持有 AUTH_GROUP + GROUP_ROLE，如角色持有 ROLES + ROLE_SCOPE）。写方法给 admin API 用；
 * 内存实现重启即空（组默认无种子，空 == 与"未引入继承"等价）。
 *
 * <p>成员关系（用户↔组）不在这里，在 {@link UserGroupStore}——组只承载"组 → 角色"绑定；两者的复合写由
 * {@code RbacMutationExecutor} 保证跨表原子。
 */
public interface GroupStore {

    Optional<Group> findByName(String name);

    List<Group> findAll();

    /** 批量按名查（登录展开继承时避免逐个 findByName 的 N+1）。未知组静默跳过。 */
    List<Group> findByNames(Collection<String> names);

    /** 原子建组：不存在则插入返回 true；已存在返回 false（不覆盖）。 */
    boolean createIfAbsent(Group group);

    /** 当前乐观锁版本号；不存在返回 {@code -1}（语义同 {@link RoleStore#versionOf}）。 */
    long versionOf(String name);

    /**
     * 条件更新组（乐观锁）：仅当当前版本 == {@code expectedVersion} 才全量替换 description/roles 并版本 +1。
     * 返回 {@code true}=已更新；{@code false}=版本不匹配（调用方已确认组存在，映射为 409）。
     */
    boolean updateIfVersion(Group group, long expectedVersion);

    /**
     * 仅推进版本（不改 description/roles），用于组成员变更时 bump 组版本——使成员编辑也走 If-Match 乐观锁、
     * 且组视图版本随任何变更单调前进。仅当版本匹配才 +1，否则返回 false。
     */
    boolean touchVersionIfVersion(String name, long expectedVersion);

    void delete(String name);

    /** 反查绑定了某角色的所有组（角色删除的引用完整性检查 + 角色收缩时撤销成员 session 用）。 */
    List<String> groupsUsingRole(String roleName);
}
