package com.lrj.platform.knowledge.ingest.sync;

/** 对外只暴露稳定错误码，避免泄露上游URL、凭据、正文或数据库异常。 */
public final class SyncFailure extends RuntimeException {
    private final String code;
    public SyncFailure(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
