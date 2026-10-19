package com.lrj.platform.knowledge.ingest.job;

/** 对外只暴露稳定码和安全消息，保留内部 cause 供既有日志排查。 */
public final class IngestionResourceException extends RuntimeException {
    private final IngestionResourceError error;
    public IngestionResourceException(IngestionResourceError error) { this(error,null); }
    public IngestionResourceException(IngestionResourceError error,Throwable cause) { super(error.message(),cause);this.error=error; }
    public IngestionResourceError error() { return error; }
}
