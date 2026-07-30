package com.lrj.platform.knowledge.ingest.job;

import java.util.Objects;

/**
 * S3-compatible 原始文档的不可变引用。原文是所有派生索引的事实源；任务和事件只传播引用，
 * 不把大文件字节写入 async-task payload。
 */
public record DocumentSourceRef(
        String bucket,
        String objectKey,
        String contentHash,
        String contentType,
        long size
) {

    public DocumentSourceRef {
        bucket = requireText(bucket, "bucket");
        objectKey = requireText(objectKey, "objectKey");
        contentHash = requireText(contentHash, "contentHash");
        contentType = requireText(contentType, "contentType");
        if (objectKey.startsWith("/") || objectKey.contains("..")) {
            throw new IllegalArgumentException("objectKey must be a normalized relative key");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " is required");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
