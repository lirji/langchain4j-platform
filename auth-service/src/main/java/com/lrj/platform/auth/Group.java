package com.lrj.platform.auth;

import java.util.Locale;
import java.util.Set;

/**
 * 用户组：一个命名的成员容器，绑定一组<b>全局角色</b>（组的成员继承这些角色展开的 scopes）。这是继承式
 * RBAC 的中间层——{@code 有效 scopes = 租户基础角色 ∪ 用户各组角色 ∪ 个人角色 ∪ 个人直配}。组与角色一样
 * <b>全局定义、与租户正交</b>：组是"给谁发"（成员容器），角色是"发什么"（能力包），租户仍决定"看哪份数据"。
 *
 * <p>组<b>不嵌套</b>（无 group→group），因此 user→group→role→scope 是固定三层 DAG，天然无环。角色→scope
 * 的展开仍由 {@code RoleService}/{@code EffectivePermissionResolver} 完成，组只承载"组→角色名"的绑定。
 *
 * <p>紧凑构造器里归一（与 {@link Role} 对齐）：{@code name} trim + 小写；{@code roles} 去空白/去重/排序后
 * 不可变；{@code description} trim 且截断到 256。归一不抛异常（迁移/加载要健壮）——名字格式校验放在写入用例
 * （{@code AdminService}），不放这里。
 *
 * @param name        组名（唯一，小写归一）
 * @param description 说明（可空）
 * @param roles       该组授予其成员的角色名集合
 */
public record Group(String name, String description, Set<String> roles) {

    /** 组名合法格式：与角色一致——小写字母开头，可含小写字母/数字/下划线/连字符，长度 1–64。 */
    public static final String NAME_PATTERN = "^[a-z][a-z0-9_-]{0,63}$";

    public Group {
        name = name == null ? null : name.trim().toLowerCase(Locale.ROOT);
        roles = UserAccount.normalize(roles);
        if (description != null) {
            description = description.trim();
            if (description.length() > 256) {
                description = description.substring(0, 256);
            }
        }
    }
}
