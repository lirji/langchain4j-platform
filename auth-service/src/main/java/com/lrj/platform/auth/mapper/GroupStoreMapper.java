package com.lrj.platform.auth.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 类型化持久化入口；SQL在同名XML，调用者不能传入SQL文本。 */
public interface GroupStoreMapper {
    /** 保留原readInit1操作的条件与影响行数契约。 */
    List<GroupStoreRow> readInit1();
    /** 保留原readInit2操作的条件与影响行数契约。 */
    List<GroupStoreRow> readInit2();
    /** 保留原readFindByName1操作的条件与影响行数契约。 */
    List<GroupStoreRow> readFindByName1(@Param("name") String name);
    /** 保留原readFindAll1操作的条件与影响行数契约。 */
    List<GroupStoreRow> readFindAll1();
    /** 保留原readVersionOf1操作的条件与影响行数契约。 */
    List<Long> readVersionOf1(@Param("name") String name);
    /** 保留原writeUpdateIfVersion1操作的条件与影响行数契约。 */
    int writeUpdateIfVersion1(@Param("description") String description, @Param("name") String name, @Param("version") Long version);
    /** 保留原writeUpdateIfVersion2操作的条件与影响行数契约。 */
    int writeUpdateIfVersion2(@Param("groupName") String groupName);
    /** 保留原writeTouchVersionIfVersion1操作的条件与影响行数契约。 */
    int writeTouchVersionIfVersion1(@Param("name") String name, @Param("version") Long version);
    /** 保留原writeDelete1操作的条件与影响行数契约。 */
    int writeDelete1(@Param("groupName") String groupName);
    /** 保留原writeDelete2操作的条件与影响行数契约。 */
    int writeDelete2(@Param("name") String name);
    /** 保留原readGroupsUsingRole1操作的条件与影响行数契约。 */
    List<String> readGroupsUsingRole1(@Param("roleName") String roleName);
    /** 保留原writeInsertGroup1操作的条件与影响行数契约。 */
    default int writeInsertGroup1(@Param("name") String name, @Param("description") String description, @Param("createdAt") Long createdAt) {
        if (restorewriteInsertGroup1(name, description, createdAt) == 1) return 1;
        return writeInsertGroup1New(name, description, createdAt);
    }
    /** 墓碑条件恢复，不覆盖现有正常记录。 */
    int restorewriteInsertGroup1(@Param("name") String name, @Param("description") String description, @Param("createdAt") Long createdAt);
    /** 不存在墓碑时插入，由数据库唯一约束处理并发创建。 */
    int writeInsertGroup1New(@Param("name") String name, @Param("description") String description, @Param("createdAt") Long createdAt);
    /** 保留原writeInsertGroupRoleIfAbsent1操作的条件与影响行数契约。 */
    default int writeInsertGroupRoleIfAbsent1(
        @Param("groupName") String groupName,
        @Param("roleName") String roleName,
        @Param("createdAt") Long createdAt) {
        if (restorewriteInsertGroupRoleIfAbsent1(groupName, roleName, createdAt) == 1) return 1;
        return writeInsertGroupRoleIfAbsent1New(groupName, roleName, createdAt);
    }
    /** 墓碑条件恢复，不覆盖现有正常记录。 */
    int restorewriteInsertGroupRoleIfAbsent1(
        @Param("groupName") String groupName,
        @Param("roleName") String roleName,
        @Param("createdAt") Long createdAt);
    /** 不存在墓碑时插入，由数据库唯一约束处理并发创建。 */
    int writeInsertGroupRoleIfAbsent1New(
        @Param("groupName") String groupName,
        @Param("roleName") String roleName,
        @Param("createdAt") Long createdAt);
    /** 保留原readRolesOf1操作的条件与影响行数契约。 */
    List<String> readRolesOf1(@Param("groupName") String groupName);
}
