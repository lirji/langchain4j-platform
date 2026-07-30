package com.lrj.platform.knowledge.ingest.job;

/** Knowledge 入库的派生存储。required sink 全部成功前文档版本不得进入 READY。 */
public enum IngestionSink {
    VECTOR(true, true, false),
    ELASTICSEARCH(true, true, false),
    GRAPH(false, true, false),
    AUTHORIZATION(true, true, false),
    REGISTRY(true, true, false);

    private final boolean requiredByDefault;
    private final boolean idempotent;
    private final boolean humanConfirmationRequired;

    IngestionSink(
            boolean requiredByDefault,
            boolean idempotent,
            boolean humanConfirmationRequired
    ) {
        this.requiredByDefault = requiredByDefault;
        this.idempotent = idempotent;
        this.humanConfirmationRequired = humanConfirmationRequired;
    }

    public boolean requiredByDefault() {
        return requiredByDefault;
    }

    public boolean idempotent() {
        return idempotent;
    }

    public boolean humanConfirmationRequired() {
        return humanConfirmationRequired;
    }
}
