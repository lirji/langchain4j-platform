package com.lrj.platform.knowledge.operations;

/** 运营业务失败只提供安全消息；底层异常不直接透传到接口。 */
public final class OperationsException extends RuntimeException {
    private final String code;
    public OperationsException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
    public static OperationsException conflict() { return new OperationsException("conflict", "operation state or version changed; refresh before retry"); }
    public static OperationsException missing() { return new OperationsException("not_found", "operation not found"); }
}
