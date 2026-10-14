package com.lrj.platform.auth.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 类型化持久化入口；SQL在同名XML，调用者不能传入SQL文本。 */
public interface RoleStoreMapper {
    /** 保留原readInit1操作的条件与影响行数契约。 */
    List<RoleStoreRow> readInit1();
    /** 保留原readInit2操作的条件与影响行数契约。 */
    List<RoleStoreRow> readInit2();
    /** 保留原readInit3操作的条件与影响行数契约。 */
    List<Integer> readInit3();
    /** 保留原readFindByName1操作的条件与影响行数契约。 */
    List<RoleStoreRow> readFindByName1(@Param("name") String name);
    /** 保留原readFindAll1操作的条件与影响行数契约。 */
    List<RoleStoreRow> readFindAll1();
    /** 保留原readVersionOf1操作的条件与影响行数契约。 */
    List<Long> readVersionOf1(@Param("name") String name);
    /** 保留原writeDoUpdate1操作的条件与影响行数契约。 */
    int writeDoUpdate1(@Param("roleName") String roleName);
    /** 保留原writeDelete1操作的条件与影响行数契约。 */
    int writeDelete1(@Param("roleName") String roleName);
    /** 保留原writeDelete2操作的条件与影响行数契约。 */
    int writeDelete2(@Param("name") String name);
    /** 保留原writeInsertRole1操作的条件与影响行数契约。 */
    default int writeInsertRole1(
        @Param("name") String name,
        @Param("scopes") String scopes,
        @Param("description") String description,
        @Param("createdAt") Long createdAt) {
        if (restorewriteInsertRole1(name, scopes, description, createdAt) == 1) return 1;
        return writeInsertRole1New(name, scopes, description, createdAt);
    }
    /** 墓碑条件恢复，不覆盖现有正常记录。 */
    int restorewriteInsertRole1(
        @Param("name") String name,
        @Param("scopes") String scopes,
        @Param("description") String description,
        @Param("createdAt") Long createdAt);
    /** 不存在墓碑时插入，由数据库唯一约束处理并发创建。 */
    int writeInsertRole1New(
        @Param("name") String name,
        @Param("scopes") String scopes,
        @Param("description") String description,
        @Param("createdAt") Long createdAt);
    /** 保留原writeInsertRoleScopeIfAbsent1操作的条件与影响行数契约。 */
    default int writeInsertRoleScopeIfAbsent1(
        @Param("roleName") String roleName,
        @Param("scope") String scope,
        @Param("createdAt") Long createdAt) {
        if (restorewriteInsertRoleScopeIfAbsent1(roleName, scope, createdAt) == 1) return 1;
        return writeInsertRoleScopeIfAbsent1New(roleName, scope, createdAt);
    }
    /** 墓碑条件恢复，不覆盖现有正常记录。 */
    int restorewriteInsertRoleScopeIfAbsent1(
        @Param("roleName") String roleName,
        @Param("scope") String scope,
        @Param("createdAt") Long createdAt);
    /** 不存在墓碑时插入，由数据库唯一约束处理并发创建。 */
    int writeInsertRoleScopeIfAbsent1New(
        @Param("roleName") String roleName,
        @Param("scope") String scope,
        @Param("createdAt") Long createdAt);
    /** 保留原readScopesOf1操作的条件与影响行数契约。 */
    List<String> readScopesOf1(@Param("roleName") String roleName);
    /** 绑定参数执行updateRole，保持既有条件和事务边界。 */
    int updateRole(
        @Param("scopes") String scopes,
        @Param("description") String description,
        @Param("name") String name,
        @Param("expectedVersion") Long expectedVersion);
}
