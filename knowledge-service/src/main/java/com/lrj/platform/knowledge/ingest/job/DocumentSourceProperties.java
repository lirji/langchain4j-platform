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
}
