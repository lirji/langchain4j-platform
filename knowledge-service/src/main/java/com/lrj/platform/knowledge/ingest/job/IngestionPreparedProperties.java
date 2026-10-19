package com.lrj.platform.knowledge.ingest.job;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 结果存储的预算和生成版本；新配置不会触发已绑定任务重新解析。 */
@ConfigurationProperties(prefix = "app.rag.ingestion.prepared")
public class IngestionPreparedProperties {
    public static final int DEFAULT_MAX_BYTES = 64 * 1024 * 1024;
    public static final String DEFAULT_PROCESSING_VERSION = "knowledge-prepared-v1";
    private int maxBytes = DEFAULT_MAX_BYTES;
    private String processingVersion = DEFAULT_PROCESSING_VERSION;

    /** UTF-8 序列化结果字节预算，同时约束存储读取。 */
    public int getMaxBytes() { return maxBytes; }
    /** 保留加一探测越界的空间，禁止零或无限制预算。 */
    public void setMaxBytes(int value) {
        if (value <= 0 || value >= Integer.MAX_VALUE) throw new IllegalArgumentException("prepared max-bytes must be positive and bounded");
        maxBytes = value;
    }
    /** 发布行为版本，不包含凭据。 */
    public String getProcessingVersion() { return processingVersion; }
    /** 版本由发布配置管理，读取历史结果不要求等于当前生成版本。 */
    public void setProcessingVersion(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("prepared processing-version is required");
        processingVersion = value.trim();
    }
}
