package com.lrj.platform.auth;

import com.lrj.platform.auth.dto.UserView;

/**
 * 登录/刷新的内部结果：会话访问令牌 + 有效期 + 刷新令牌原串（由 controller 写入 httpOnly cookie）+ 用户视图。
 * {@code rawRefreshToken} 绝不进响应体，仅用于 Set-Cookie。
 */
public record AuthResult(String accessToken,
                         long expiresInSeconds,
                         String rawRefreshToken,
                         UserView user) {
}
