package com.lrj.platform.auth.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 类型化持久化入口；SQL在同名XML，调用者不能传入SQL文本。 */
public interface UserAccountStoreMapper {
    /** 保留原readInit1操作的条件与影响行数契约。 */
    List<UserAccountStoreRow> readInit1();
    /** 保留原readInit2操作的条件与影响行数契约。 */
    List<UserAccountStoreRow> readInit2();
    /** 保留原readInit3操作的条件与影响行数契约。 */
    List<Integer> readInit3();
    /** 保留原readCount1操作的条件与影响行数契约。 */
    List<Integer> readCount1();
    /** 保留原readFindByRole1操作的条件与影响行数契约。 */
    List<String> readFindByRole1(@Param("roleName") String roleName);
    /** 保留原readDistinctTenants1操作的条件与影响行数契约。 */
    List<String> readDistinctTenants1();
    /** 保留原writeTouchVersionIfVersion1操作的条件与影响行数契约。 */
    int writeTouchVersionIfVersion1(@Param("username") String username, @Param("version") Long version);
    /** 保留原writeUpdateProfile1操作的条件与影响行数契约。 */
    int writeUpdateProfile1(
        @Param("passwordHash") String passwordHash,
        @Param("tenant") String tenant,
        @Param("scopes") String scopes,
        @Param("enabled") Boolean enabled,
        @Param("username") String username);
    /** 保留原readVersionOf1操作的条件与影响行数契约。 */
    List<Long> readVersionOf1(@Param("username") String username);
    /** 保留原writeUpdateProfileIfVersion1操作的条件与影响行数契约。 */
    int writeUpdateProfileIfVersion1(
        @Param("passwordHash") String passwordHash,
        @Param("tenant") String tenant,
        @Param("scopes") String scopes,
        @Param("enabled") Boolean enabled,
        @Param("username") String username,
        @Param("version") Long version);
    /** 保留原writeDoReplaceRoles1操作的条件与影响行数契约。 */
    int writeDoReplaceRoles1(@Param("username") String username);
    /** 保留原writeDelete1操作的条件与影响行数契约。 */
    int writeDelete1(@Param("username") String username);
    /** 保留原writeDelete2操作的条件与影响行数契约。 */
    int writeDelete2(@Param("username") String username);
    /** 保留原writeInsertUser1操作的条件与影响行数契约。 */
    int writeInsertUser1(
        @Param("username") String username,
        @Param("passwordHash") String passwordHash,
        @Param("tenant") String tenant,
        @Param("userId") String userId,
        @Param("scopes") String scopes,
        @Param("roles") String roles,
        @Param("enabled") Boolean enabled,
        @Param("createdAt") Long createdAt);
    /** 保留原writeInsertUserRoleIfAbsent1操作的条件与影响行数契约。 */
    int writeInsertUserRoleIfAbsent1(
        @Param("username") String username,
        @Param("roleName") String roleName,
        @Param("createdAt") Long createdAt);
    /** 保留原readRolesOf1操作的条件与影响行数契约。 */
    List<String> readRolesOf1(@Param("username") String username);
    /** 绑定参数执行findUsers1，保持既有条件和事务边界。 */
    List<UserAccountStoreRow> findUsers1(@Param("username") String username);
    /** 绑定参数执行findUsers2，保持既有条件和事务边界。 */
    List<UserAccountStoreRow> findUsers2();
    /** 绑定参数执行findUsers3，保持既有条件和事务边界。 */
    List<UserAccountStoreRow> findUsers3(@Param("limit") Integer limit, @Param("offset") Integer offset);
    /** 绑定参数执行findUsers4，保持既有条件和事务边界。 */
    List<UserAccountStoreRow> findUsers4(@Param("tenant") String tenant);
    /** 绑定参数执行updateRoles，保持既有条件和事务边界。 */
    int updateRoles(@Param("roles") String roles, @Param("username") String username, @Param("expectedVersion") Long expectedVersion);
}
