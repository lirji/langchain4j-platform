package com.lrj.platform.auth;

import java.util.List;
import java.util.Set;

/**
 * 演示种子角色：把散落在既有账号/网关 api-key 上的 scope 集合命名成角色，作为 RBAC 的默认字典。
 * 内存 store 直接用；JDBC store 在 ROLES 表为空时用它初始化。生产应改为落库自管。
 *
 * <p>新增两个平台 scope：{@code role-admin}（管账号/角色的 admin API）、{@code public-ingest}
 * （写公共/共享知识库）。二者归入 {@code admin} 角色。
 */
final class SeedRoles {

    private SeedRoles() {}

    static List<Role> defaults() {
        return List.of(
                new Role("viewer", Set.of("chat"), "只读对话"),
                new Role("editor", Set.of("chat", "ingest"), "对话 + 知识库入库"),
                new Role("analyst", Set.of("chat", "analytics"), "对话 + 数据分析"),
                new Role("approver", Set.of("chat", "approve"), "对话 + 审批"),
                new Role("admin",
                        Set.of("chat", "ingest", "approve", "agent", "channel", "eval",
                                "vision", "voice", "analytics", "role-admin", "public-ingest"),
                        "全权限：含管理角色/账号与写公共库"));
    }
}
