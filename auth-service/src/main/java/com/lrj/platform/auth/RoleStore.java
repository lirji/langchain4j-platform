package com.lrj.platform.auth;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 角色读写。接口 + 内存/JDBC 双实现（沿用项目"接口 + @ConditionalOnProperty 切换"主导写法）。
 * 写方法给 admin API 用；内存实现重启即回到种子态。
 *
 * <p>RBAC 原子语义：{@link #createIfAbsent} / {@link #update} 取代含糊的 upsert-{@code save}——
 * 建角色不覆盖同名、改角色不存在即失败；{@link #findByNames} 供登录时批量展开角色。
 */
public interface RoleStore {

    Optional<Role> findByName(String name);

    List<Role> findAll();

    /** 批量按名查（登录展开 effective scopes 用，避免逐个 findByName 的 N+1）。 */
    default List<Role> findByNames(Collection<String> names) {
        throw new UnsupportedOperationException("findByNames not supported by this store");
    }

    /** 新增或覆盖同名角色（旧 upsert 语义；新代码优先用 createIfAbsent/update）。 */
    void save(Role role);

    /** 原子建角色：不存在则插入返回 true；已存在返回 false（不覆盖）。 */
    default boolean createIfAbsent(Role role) {
        throw new UnsupportedOperationException("createIfAbsent not supported by this store");
    }

    /** 更新角色 scopes/description（全量替换）；不存在返回 false。 */
    default boolean update(Role role) {
        throw new UnsupportedOperationException("update not supported by this store");
    }

    void delete(String name);

    // ---- 乐观锁（并发编辑防静默覆盖）----

    /** 当前乐观锁版本号；不存在返回 {@code -1}。语义同 {@link UserAccountStore#versionOf}。 */
    default long versionOf(String name) {
        throw new UnsupportedOperationException("versionOf not supported by this store");
    }

    /**
     * 条件更新角色（乐观锁）：仅当当前版本 == {@code expectedVersion} 才全量替换 scopes/description 并版本 +1。
     * 返回 {@code true}=已更新；{@code false}=版本不匹配（调用方已确认角色存在，映射为 409 冲突）。
     */
    default boolean updateIfVersion(Role role, long expectedVersion) {
        throw new UnsupportedOperationException("updateIfVersion not supported by this store");
    }
}
