package com.lrj.platform.knowledge.ingest.job;

/** 派生结果的稳定故障目录；任务只公开错误码，不泄漏对象地址或文档正文。 */
public enum PreparedResultError {
    INVALID("PREPARED_RESULT_INVALID", "prepared result failed integrity validation", false),
    LIMIT("PREPARED_RESULT_LIMIT", "prepared result exceeds the configured byte limit", false),
    VERSION("PREPARED_RESULT_VERSION", "prepared result format is not supported by this worker", false),
    UNAVAILABLE("PREPARED_RESULT_UNAVAILABLE", "prepared result storage is unavailable", true),
    LEGACY("LEGACY_PREPARATION_REQUIRED", "legacy in-flight job requires controlled recovery", false);

    private final String code;
    private final String message;
    private final boolean retryable;

    PreparedResultError(String code, String message, boolean retryable) {
        this.code = code;
        this.message = message;
        this.retryable = retryable;
    }

    /** 写入既有任务 errorCode 字段。 */
    public String code() { return code; }
    /** 对外安全消息，内部原因保留在异常链。 */
    public String message() { return message; }
    /** 只有对象存储的暂时故障允许有限重试，绝不因此重新解析。 */
    public boolean retryable() { return retryable; }
}
