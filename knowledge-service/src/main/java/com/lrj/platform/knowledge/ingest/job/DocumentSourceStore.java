package com.lrj.platform.knowledge.ingest.job;

import java.io.IOException;
import java.io.InputStream;

/**
 * 原始文档对象存储端口。生产适配器必须指向 S3-compatible object storage；领域代码不依赖具体 SDK。
 */
public interface DocumentSourceStore {

    DocumentSourceRef put(PutSource command) throws IOException;

    /** 可重开文件用于有界上传与 SDK 重试；不要求所有适配器在内存保留全部输入。 */
    default DocumentSourceRef putFile(SourceDescriptor source, java.nio.file.Path file) throws IOException {
        try (var input = java.nio.file.Files.newInputStream(file)) { return put(source.withContent(input)); }
    }

    /** 对象身份先由完整 hash 决定，不使用未核验的请求头指纹命名对象。 */
    record SourceDescriptor(String tenantId, String documentId, long version, String contentType, long size, String contentHash) {
        public PutSource withContent(InputStream content) { return new PutSource(tenantId,documentId,version,contentType,size,contentHash,content); }
    }

    InputStream open(String tenantId, DocumentSourceRef source) throws IOException;

    void delete(String tenantId, DocumentSourceRef source) throws IOException;

    record PutSource(
            String tenantId,
            String documentId,
            long version,
            String contentType,
            long size,
            String contentHash,
            InputStream content
    ) {
    }
}
