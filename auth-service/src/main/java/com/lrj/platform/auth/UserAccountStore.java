package com.lrj.platform.auth;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 用户账号读写。接口 + 内存/JDBC 双实现（沿用项目"接口 + @ConditionalOnProperty 切换"主导写法）。
 *
 * <p>{@link #findByUsername} 是唯一抽象方法，故本接口仍是<b>函数式接口</b>（既有把它当 lambda 的测试不受影响）。
 * 写方法给 admin API / 注册用，默认抛 {@link UnsupportedOperationException}，由真实 store 覆盖。
 *
 * <p>RBAC 原子语义：{@link #createIfAbsent} 用底层唯一键（putIfAbsent / 主键）避免注册的
 * find-then-save 覆盖；{@link #replaceRoles} 全量替换角色（幂等 PUT）；{@link #findByRole} 供角色
 * 降权时反查受影响用户。复合写的跨记录原子性由 {@code RbacMutationExecutor} 保证。
 */
public interface UserAccountStore {

    Optional<UserAccount> findByUsername(String username);

    /** 新增或覆盖账号（admin 建户）。 */
    default void save(UserAccount account) {
        throw new UnsupportedOperationException("save not supported by this store");
    }

    /** 更新账号（admin 改角色/租户/启用态）。 */
    default void update(UserAccount account) {
        throw new UnsupportedOperationException("update not supported by this store");
    }

    default List<UserAccount> findAll() {
        throw new UnsupportedOperationException("findAll not supported by this store");
    }

    default void delete(String username) {
        throw new UnsupportedOperationException("delete not supported by this store");
    }

    // ---- RBAC 原子写 / 分页 / 反查 ----

    /** 原子建号：不存在则插入返回 true；已存在返回 false（不覆盖）。 */
    default boolean createIfAbsent(UserAccount account) {
        throw new UnsupportedOperationException("createIfAbsent not supported by this store");
    }

    /** 更新账号资料（租户/口令/直配 scopes/启用态），<b>不改角色</b>；不存在返回 false。 */
    default boolean updateProfile(String username, String tenant, String passwordHash,
                                  Set<String> directScopes, boolean enabled) {
        throw new UnsupportedOperationException("updateProfile not supported by this store");
    }

    /** 全量替换用户角色（幂等）；不存在返回 false。 */
    default boolean replaceRoles(String username, Set<String> roles) {
        throw new UnsupportedOperationException("replaceRoles not supported by this store");
    }

    /** 反查绑定了某角色的所有用户（角色 scope 降权时撤销其 refresh session 用）。 */
    default List<UserAccount> findByRole(String roleName) {
        throw new UnsupportedOperationException("findByRole not supported by this store");
    }

    /** 反查某租户下的所有用户（租户基础角色降权时撤销 refresh session、租户内最后管理员保护用）。 */
    default List<UserAccount> findByTenant(String tenant) {
        throw new UnsupportedOperationException("findByTenant not supported by this store");
    }

    /** 列出所有用户实际用到的租户（去重、排序），供租户管理页并上仅配了策略的租户。 */
    default List<String> distinctTenants() {
        throw new UnsupportedOperationException("distinctTenants not supported by this store");
    }

    /** 分页列出用户（按 username 稳定排序）。 */
    default List<UserAccount> findPage(int offset, int limit) {
        throw new UnsupportedOperationException("findPage not supported by this store");
    }

    /** 用户总数（分页 X-Total-Count 用）。 */
    default int count() {
        throw new UnsupportedOperationException("count not supported by this store");
    }

    // ---- 乐观锁（并发编辑防静默覆盖）----

    /**
     * 当前乐观锁版本号；不存在返回 {@code -1}。视图用它填充 {@code version}，管理端回写时以
     * {@code If-Match} 头带回该值做 compare-and-set。非版本化写（如注册、全量 update）也会递增版本，
     * 使版本号始终随任何变更单调前进。
     */
    default long versionOf(String username) {
        throw new UnsupportedOperationException("versionOf not supported by this store");
    }

    /**
     * 条件更新资料（乐观锁）：仅当当前版本 == {@code expectedVersion} 才更新并版本 +1，<b>不改角色</b>。
     * 返回 {@code true}=已更新；{@code false}=版本不匹配（调用方已在事务/临界区内确认用户存在，故映射为 409 冲突）。
     */
    default boolean updateProfileIfVersion(String username, String tenant, String passwordHash,
                                           Set<String> directScopes, boolean enabled, long expectedVersion) {
        throw new UnsupportedOperationException("updateProfileIfVersion not supported by this store");
    }

    /** 条件全量替换角色（乐观锁），语义同 {@link #updateProfileIfVersion}。 */
    default boolean replaceRolesIfVersion(String username, Set<String> roles, long expectedVersion) {
        throw new UnsupportedOperationException("replaceRolesIfVersion not supported by this store");
    }

    /**
     * 仅推进用户版本（不改任何字段），用于"改用户组绑定"时 bump USERS.VERSION——使组编辑也走 If-Match 乐观锁、
     * 用户视图版本随任何变更单调前进。仅当当前版本 == {@code expectedVersion} 才 +1，否则返回 false。
     */
    default boolean touchVersionIfVersion(String username, long expectedVersion) {
        throw new UnsupportedOperationException("touchVersionIfVersion not supported by this store");
    }
}
