package com.lrj.platform.auth;

import java.util.Locale;
import java.util.Set;

/**
 * RBAC 角色：一个命名的 scope 集合。角色是全局定义（与租户正交）——租户挂在 {@link UserAccount}
 * 上决定"看到哪份数据"，角色决定"能做哪些操作"（scopes）。登录时 {@code RoleService} 把用户的
 * 角色展开成 scopes 签进会话 JWT，下游只认 scope，对角色无感知。
 *
 * <p>紧凑构造器里做归一：{@code name} trim + 小写；{@code scopes} 去空白/去重/排序后不可变；
 * {@code description} trim 且截断到 256。归一不抛异常（迁移/加载路径要健壮）——名字格式校验放在
 * 写入用例（{@code RoleService}/{@code AdminService}），不放这里，避免读旧数据时崩。
 *
 * @param name        角色名（唯一，小写归一）
 * @param scopes      该角色授予的 scope 集合
 * @param description 说明（可空）
 */
public record Role(String name, Set<String> scopes, String description) {

    /** 角色名合法格式：小写字母开头，可含小写字母/数字/下划线/连字符，长度 1–64。 */
    public static final String NAME_PATTERN = "^[a-z][a-z0-9_-]{0,63}$";

    public Role {
        name = name == null ? null : name.trim().toLowerCase(Locale.ROOT);
        scopes = UserAccount.normalize(scopes);
        if (description != null) {
            description = description.trim();
            if (description.length() > 256) {
                description = description.substring(0, 256);
            }
        }
    }
}
