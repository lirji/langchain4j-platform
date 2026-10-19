package com.lrj.platform.knowledge.ingest.job;

/** 入库资源错误统一目录；HTTP 属性与任务重试属性分开，避免资源超限反复调用计费模型。 */
public enum IngestionResourceError {
    INPUT_LIMIT("INGESTION_INPUT_LIMIT","document exceeds ingestion input limit",413,false),
    INPUT_SIZE("INGESTION_INPUT_SIZE","document size does not match the supplied size",400,false),
    UPLOAD_BUSY("INGESTION_UPLOAD_BUSY","ingestion upload capacity is busy; retry later",429,true),
    INPUT_UNAVAILABLE("INGESTION_INPUT_UNAVAILABLE","document upload could not be completed; retry later",503,true),
    MODEL_INPUT_LIMIT("INGESTION_MODEL_INPUT_LIMIT","document exceeds model input budget",400,false),
    MODEL_RESPONSE("INGESTION_MODEL_RESPONSE_INVALID","embedding response is incomplete or invalid",503,false),
    VECTOR_LIMIT("INGESTION_VECTOR_LIMIT","document exceeds prepared vector budget",400,false),
    BATCH_LIMIT("INGESTION_BATCH_LIMIT","document segment exceeds sink batch budget",400,false),
    DEADLINE("INGESTION_STAGE_DEADLINE","ingestion stage exceeded its execution budget",503,false);
    private final String code,message; private final int status; private final boolean retryable;
    IngestionResourceError(String code,String message,int status,boolean retryable) {this.code=code;this.message=message;this.status=status;this.retryable=retryable;}
    public String code(){return code;}
    public String message(){return message;}
    public int status(){return status;}
    public boolean retryable(){return retryable;}
}
