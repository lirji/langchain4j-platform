package com.lrj.platform.knowledge.ingest.job;

import com.lrj.platform.knowledge.lifecycle.DocumentInfo;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 快照恢复测试复用真实非空内容，避免空 mock 绕过准备协议。 */
final class PreparedIngestionFixtures {
    static final Instant NOW = Instant.parse("2026-10-19T00:00:00Z");
    static IngestionJob job(String id) {
        return IngestionJob.received(id, "key-" + id, "acme", "alice", Set.of("ingest"), "engineering", "trace",
                "doc-" + id, "技术文档.txt", "manual", 1, true,
                new DocumentSourceRef("sources", "acme/source", "sha256:source", "text/plain", 12),
                Set.of(IngestionSink.VECTOR, IngestionSink.ELASTICSEARCH, IngestionSink.REGISTRY), NOW);
    }
    static PreparedIngestionDocument prepared(IngestionJob job) {
        TextSegment segment = TextSegment.from("content 中文");
        segment.metadata().put("tenantId", job.tenantId()).put("docId", job.documentId())
                .put("version", Long.toString(job.documentVersion())).put("index", "0");
        return new PreparedIngestionDocument(new DocumentInfo(job.documentId(), job.tenantId(), job.displayName(),
                job.source().contentType(), job.source().size(), 1, (int) job.documentVersion(), NOW, job.category()),
                List.of(segment), List.of(Embedding.from(new float[]{0.125f, -2.5f, Float.MIN_VALUE})));
    }
}
