package com.lrj.platform.knowledge.ingest.job;

/** 准备结果错误使用显式类别，避免按底层异常文案决定是否重新执行。 */
public final class PreparedResultException extends RuntimeException {
    private final PreparedResultError error;

    public PreparedResultException(PreparedResultError error) { this(error, null); }

    public PreparedResultException(PreparedResultError error, Throwable cause) {
        super(error.message(), cause);
        this.error = error;
    }

    /** 重试策略复用稳定分类。 */
    public PreparedResultError error() { return error; }
}
