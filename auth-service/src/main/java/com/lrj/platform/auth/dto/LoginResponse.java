package com.lrj.platform.auth.dto;

/**
 * 登录/刷新响应体。{@code accessToken} 前端存内存并作 {@code Authorization: Bearer} 使用；
 * 刷新令牌不在此，走 httpOnly cookie。
 */
public record LoginResponse(String accessToken, long expiresInSeconds, UserView user) {
}
