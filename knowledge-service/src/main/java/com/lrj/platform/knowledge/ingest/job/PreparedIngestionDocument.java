package com.lrj.platform.knowledge.ingest.job;

import com.lrj.platform.knowledge.lifecycle.DocumentInfo;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;

import java.util.List;
import java.util.Objects;

/**
 * 从权威原文准备或从不可变快照恢复的派生数据；持久化格式由结果存储的版本化 DTO 定义。
 */
public record PreparedIngestionDocument(
        DocumentInfo info,
        List<TextSegment> segments,
        List<Embedding> embeddings
) {

    public PreparedIngestionDocument {
        Objects.requireNonNull(info, "info is required");
        segments = List.copyOf(Objects.requireNonNull(segments, "segments are required"));
        embeddings = List.copyOf(Objects.requireNonNull(embeddings, "embeddings are required"));
        if (segments.isEmpty() || segments.size() != embeddings.size()) {
            throw new IllegalArgumentException(
                    "non-empty segments and same-sized embeddings are required");
        }
    }
}
