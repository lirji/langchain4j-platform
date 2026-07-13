package com.lrj.platform.auth.dto;

/**
 * 公开的最小非敏感配置，供未登录前端在渲染登录/注册页前拉取（{@code GET /auth/public-config}，边缘 open 路径）。
 * 只暴露"注册入口是否可用 + 密码长度约束"，<b>绝不</b>包含任何密钥、规则明细、租户/角色映射。
 *
 * <p>{@code registrationEnabled} 反映真实可用性：当前实现要求 RBAC 与 registration 同时开启注册才会成功，
 * 故此值 = {@code rbac.enabled && registration.enabled}，前端据此显示/隐藏注册入口，避免点进去必然 403。
 */
public record AuthPublicConfig(boolean registrationEnabled, int passwordMinLength, int passwordMaxLength) {}
