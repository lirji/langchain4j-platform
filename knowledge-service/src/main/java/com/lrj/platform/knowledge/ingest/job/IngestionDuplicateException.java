package com.lrj.platform.knowledge.ingest.job;

/** 内容相同是可恢复的业务结果；仅携带经授权的引用，不暴露他人的任务详情和排障数据。 */
public final class IngestionDuplicateException extends RuntimeException {
    private final Kind kind;
    private final Reference duplicate;

    public IngestionDuplicateException(Kind kind, Reference duplicate) {
        super(kind.message());
        this.kind = kind;
        this.duplicate = duplicate;
    }

    public Kind kind() { return kind; }
    public Reference duplicate() { return duplicate; }

    /** 显式稳定 code，与前端按错误码判断的契约一致。 */
    public enum Kind {
        READY("INGESTION_DUPLICATE_READY", "identical content is already available"),
        PROCESSING("INGESTION_DUPLICATE_PROCESSING", "identical content has already been submitted"),
        ATTENTION("INGESTION_DUPLICATE_ATTENTION", "identical content has an existing task that needs recovery");
        private final String code, message;
        Kind(String code, String message) { this.code = code; this.message = message; }
        public String code() { return code; }
        public String message() { return message; }
    }

    /** jobId 仅本人可见；未发布的他人任务不返回文档元数据，不能拿它构造可检索回执。 */
    public record Reference(String documentId, String displayName, Long documentVersion,
                            String jobId, boolean documentAvailable) { }
}
