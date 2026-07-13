package com.lrj.platform.auth.dto;

/**
 * 自助注册请求。{@code username} 可为邮箱形态（{@code user@acme.com}），注册规则引擎据其域名
 * 映射租户+角色；否则落默认租户+默认角色。
 */
public record RegisterRequest(String username, String password) {}
