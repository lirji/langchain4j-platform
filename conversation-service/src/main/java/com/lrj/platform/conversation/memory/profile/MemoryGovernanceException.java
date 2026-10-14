package com.lrj.platform.conversation.memory.profile;

/** 治理业务失败与数据库异常分开，边界按稳定错误码映射。 */
public final class MemoryGovernanceException extends RuntimeException {
    private final String code;
    /** 不接受数据库错误作为消息，避免把 SQL 或正文带给调用者。 */
    public MemoryGovernanceException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
