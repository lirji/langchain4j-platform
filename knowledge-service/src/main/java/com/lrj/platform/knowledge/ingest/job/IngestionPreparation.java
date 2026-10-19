package com.lrj.platform.knowledge.ingest.job;

/** 任务中的小型准备标记与不可变结果引用；NULL 专门表示无法证明安全恢复的旧任务。 */
public record IngestionPreparation(int schemaVersion, String processingVersion, DocumentSourceRef result) {
    public static final int CURRENT_SCHEMA = 1;
    public static final IngestionPreparation PENDING = new IngestionPreparation(CURRENT_SCHEMA, null, null);

    public IngestionPreparation {
        if (schemaVersion < 1 || (result == null) != (processingVersion == null)
                || (processingVersion != null && processingVersion.isBlank())) {
            throw new IllegalArgumentException("invalid ingestion preparation reference");
        }
    }

    /** 标记存在但无对象意味着从未允许执行任何 sink，可以安全地重做准备。 */
    public boolean bound() { return result != null; }
}
