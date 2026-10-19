package com.lrj.platform.knowledge.ingest.job;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.rag.source.*}。dev/test 默认 memory；生产 ingest worker 必须显式配置 store=s3。
 */
@ConfigurationProperties(prefix = "app.rag.source")
public class DocumentSourceProperties {

    private String store = "memory";
    private String endpoint = "";
    private String region = "us-east-1";
    private String bucket = "knowledge-sources";
    private String prefix = "documents";
    private String accessKey = "";
    private String secretKey = "";
    private boolean pathStyle = true;
    private java.time.Duration connectTimeout = java.time.Duration.ofSeconds(2);
    private java.time.Duration readTimeout = java.time.Duration.ofSeconds(30);
    private java.time.Duration callTimeout = java.time.Duration.ofMinutes(2);

    public String getStore() { return store; }
    public void setStore(String store) { this.store = store; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public boolean isPathStyle() { return pathStyle; }
    public void setPathStyle(boolean pathStyle) { this.pathStyle = pathStyle; }
    /** 原文/快照共用的连接、流读取和 SDK 调用预算，防止暂存前无限等待远端。 */
    public java.time.Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(java.time.Duration value) { connectTimeout = positive(value); }
    public java.time.Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(java.time.Duration value) { readTimeout = positive(value); }
    public java.time.Duration getCallTimeout() { return callTimeout; }
    public void setCallTimeout(java.time.Duration value) { callTimeout = positive(value); }
    private static java.time.Duration positive(java.time.Duration value) {
        if (value == null || value.isNegative() || value.isZero()) throw new IllegalArgumentException("source timeouts must be positive");
        return value;
    }
}
