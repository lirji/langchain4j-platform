package com.lrj.platform.knowledge.ingest.job;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.time.Duration;

/** 入库输入与模型/存储批次保护预算；这些缺省值不是生产吞吐或模型最大上下文承诺。 */
@Component
@ConfigurationProperties(prefix = "app.rag.ingestion.resources")
public class IngestionResourceProperties {
    private long maxInputBytes = 50L * 1024 * 1024;
    private int maxUploads = 4, maxUploadsPerTenant = 1;
    private Duration uploadTimeout = Duration.ofSeconds(60);
    private Path uploadTempRoot = Path.of(System.getProperty("java.io.tmpdir"),"knowledge-uploads");
    private int maxBatchSegments = 32, maxBatchTokens = 8192, maxSegmentTokens = 2048, maxDocumentTokens = 500_000;
    private int maxBatchBytes = 1024 * 1024, maxDocumentBytes = 16 * 1024 * 1024;
    private long maxVectorBytes = 32L * 1024 * 1024;
    private String tokenizerModel = "gpt-4o-mini";
    private Duration preparationTimeout = Duration.ofMinutes(10);
    private long maxModelInputTokens = 1_000_000, maxMetadataBytes = 16L * 1024 * 1024;
    private int maxModelCalls = 512, maxContextualCalls = 128, maxContextPromptTokens = 8192, maxContextPrefixTokens = 256;
    private int maxContextualConsecutiveFailures = 3;
    private int maxContentCandidates = 100, maxContentionRetries = 3;

    /** 历史重复候选与并发重新检查均有界；耗尽预算时不跳过去重。 */
    public int getMaxContentCandidates() { return maxContentCandidates; }
    public void setMaxContentCandidates(int value) {
        positive(value);
        if (value == Integer.MAX_VALUE) throw new IllegalArgumentException("invalid content candidate limit");
        maxContentCandidates = value;
    }
    public int getMaxContentionRetries() { return maxContentionRetries; }
    public void setMaxContentionRetries(int value) { positive(value); maxContentionRetries = value; }

    /** 输入限制与 servlet 上传限制需同时配置；实际字节数始终重新计量。 */
    public long getMaxInputBytes() { return maxInputBytes; }
    public void setMaxInputBytes(long value) { positive(value); maxInputBytes=value; }
    public int getMaxUploads() { return maxUploads; }
    public void setMaxUploads(int value) { positive(value); maxUploads=value; }
    public int getMaxUploadsPerTenant() { return maxUploadsPerTenant; }
    public void setMaxUploadsPerTenant(int value) { positive(value); maxUploadsPerTenant=value; }
    public Duration getUploadTimeout() { return uploadTimeout; }
    public void setUploadTimeout(Duration value) { positive(value.toMillis()); uploadTimeout=value; }
    public Path getUploadTempRoot() { return uploadTempRoot; }
    public void setUploadTempRoot(Path value) { uploadTempRoot=java.util.Objects.requireNonNull(value).toAbsolutePath(); }
    /** 批次三种预算同时满足；token 是指定 tokenizer 的本地估计，需按实际模型调整。 */
    public int getMaxBatchSegments() { return maxBatchSegments; }
    public void setMaxBatchSegments(int value) { positive(value); maxBatchSegments=value; }
    public int getMaxBatchTokens() { return maxBatchTokens; }
    public void setMaxBatchTokens(int value) { positive(value); maxBatchTokens=value; }
    public int getMaxSegmentTokens() { return maxSegmentTokens; }
    public void setMaxSegmentTokens(int value) { positive(value); maxSegmentTokens=value; }
    public int getMaxDocumentTokens() { return maxDocumentTokens; }
    public void setMaxDocumentTokens(int value) { positive(value); maxDocumentTokens=value; }
    public int getMaxBatchBytes() { return maxBatchBytes; }
    public void setMaxBatchBytes(int value) { positive(value); maxBatchBytes=value; }
    public int getMaxDocumentBytes() { return maxDocumentBytes; }
    public void setMaxDocumentBytes(int value) { positive(value); maxDocumentBytes=value; }
    public long getMaxVectorBytes() { return maxVectorBytes; }
    public void setMaxVectorBytes(long value) { positive(value); maxVectorBytes=value; }
    public String getTokenizerModel() { return tokenizerModel; }
    public void setTokenizerModel(String value) { if(value==null || value.isBlank()) throw new IllegalArgumentException("ingestion tokenizer-model is required"); tokenizerModel=value; }
    /** 每批前后检查预算；单次远程 I/O 仍由模型/存储客户端超时终止等待。 */
    public Duration getPreparationTimeout() { return preparationTimeout; }
    public void setPreparationTimeout(Duration value) { positive(value.toMillis()); preparationTimeout=value; }
    /** 跨语义窗口、上下文与最终向量的每次准备预算；不是租户账单或提供方精确 token。 */
    public long getMaxModelInputTokens() { return maxModelInputTokens; }
    public void setMaxModelInputTokens(long value) { positive(value); maxModelInputTokens=value; }
    public int getMaxModelCalls() { return maxModelCalls; }
    public void setMaxModelCalls(int value) { positive(value); maxModelCalls=value; }
    public int getMaxContextualCalls() { return maxContextualCalls; }
    public void setMaxContextualCalls(int value) { positive(value); maxContextualCalls=value; }
    /** 同一文档的可选增强连续异常后停止后续调用，避免不可用模型放大整文等待。 */
    public int getMaxContextualConsecutiveFailures() { return maxContextualConsecutiveFailures; }
    /** 拒绝零或负数，避免配置意外关闭所有尝试或使失败保护失效。 */
    public void setMaxContextualConsecutiveFailures(int value) { positive(value); maxContextualConsecutiveFailures=value; }
    public int getMaxContextPromptTokens() { return maxContextPromptTokens; }
    public void setMaxContextPromptTokens(int value) { positive(value); maxContextPromptTokens=value; }
    public int getMaxContextPrefixTokens() { return maxContextPrefixTokens; }
    public void setMaxContextPrefixTokens(int value) { positive(value); maxContextPrefixTokens=value; }
    /** metadata 包含重复父块与原文，必须在最终模型调用和 sink 前校验整体放大。 */
    public long getMaxMetadataBytes() { return maxMetadataBytes; }
    public void setMaxMetadataBytes(long value) { positive(value); maxMetadataBytes=value; }
    private static void positive(long value) { if(value<=0) throw new IllegalArgumentException("ingestion resource limits must be positive"); }
}
