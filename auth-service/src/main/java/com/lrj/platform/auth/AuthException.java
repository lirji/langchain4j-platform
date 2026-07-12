package com.lrj.platform.auth;

/** 认证流程业务异常，携带要返回的 HTTP 状态码与错误码。由 {@code AuthController} 统一映射为响应。 */
public class AuthException extends RuntimeException {

    private final int status;
    private final String code;

    public AuthException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
}
