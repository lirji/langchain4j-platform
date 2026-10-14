package com.lrj.platform.auth.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 类型化持久化入口；SQL在同名XML，调用者不能传入SQL文本。 */
public interface UserGroupStoreMapper {
    /** 保留原readInit1操作的条件与影响行数契约。 */
    List<UserGroupStoreRow> readInit1();
    /** 保留原readGroupsOf1操作的条件与影响行数契约。 */
    List<String> readGroupsOf1(@Param("username") String username);
    /** 保留原readMembersOf1操作的条件与影响行数契约。 */
    List<String> readMembersOf1(@Param("groupName") String groupName);
    /** 保留原writeReplaceGroupsForUser1操作的条件与影响行数契约。 */
    int writeReplaceGroupsForUser1(@Param("username") String username);
    /** 保留原writeReplaceMembersForGroup1操作的条件与影响行数契约。 */
    int writeReplaceMembersForGroup1(@Param("groupName") String groupName);
    /** 保留原writeRemoveAllForUser1操作的条件与影响行数契约。 */
    int writeRemoveAllForUser1(@Param("username") String username);
    /** 保留原writeRemoveAllForGroup1操作的条件与影响行数契约。 */
    int writeRemoveAllForGroup1(@Param("groupName") String groupName);
    /** 保留原writeInsertIfAbsent1操作的条件与影响行数契约。 */
    int writeInsertIfAbsent1(@Param("username") String username, @Param("groupName") String groupName, @Param("createdAt") Long createdAt);
}
