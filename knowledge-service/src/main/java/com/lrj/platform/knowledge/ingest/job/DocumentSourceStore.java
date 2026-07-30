package com.lrj.platform.knowledge.ingest.job;

import java.io.IOException;
import java.io.InputStream;

/**
 * 原始文档对象存储端口。生产适配器必须指向 S3-compatible object storage；领域代码不依赖具体 SDK。
 */
public interface DocumentSourceStore {

    DocumentSourceRef put(PutSource command) throws IOException;

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
