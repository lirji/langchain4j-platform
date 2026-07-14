package com.lrj.platform.auth;

import java.util.List;
import java.util.Set;

/**
 * 用户↔组成员关系（多对多）。接口 + 内存/JDBC 双实现，语义镜像 USER_ROLE 的关系化写/反查。用户经组继承组
 * 绑定的角色——{@link #groupsOf} 供登录展开，{@link #membersOf} 供组侧成员编辑与"组降权时反查受影响成员"。
 *
 * <p>成员集与用户/组本身解耦：删用户/删组时由 {@code AdminService} 在事务内显式级联清理（{@link #removeAllForUser}
 * / {@link #removeAllForGroup}），不建外键。
 */
public interface UserGroupStore {

    /** 某用户所属的组名集合（登录展开继承用）；无则空集。 */
    Set<String> groupsOf(String username);

    /** 某组的成员用户名列表（组侧成员编辑 + 组降权反查用）；无则空列表。 */
    List<String> membersOf(String group);

    /** 全量替换某用户的组集（幂等，用户侧 PUT /users/{u}/groups）。 */
    void replaceGroupsForUser(String username, Set<String> groups);

    /** 全量替换某组的成员集（幂等，组侧 PUT /groups/{g}/members）。 */
    void replaceMembersForGroup(String group, Set<String> members);

    /** 删用户时级联清其全部组成员行。 */
    void removeAllForUser(String username);

    /** 删组时级联清其全部成员行。 */
    void removeAllForGroup(String group);
}
