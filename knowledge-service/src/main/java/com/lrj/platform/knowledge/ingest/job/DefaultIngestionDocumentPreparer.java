package com.lrj.platform.knowledge.ingest.job;

import com.lrj.platform.knowledge.DocumentSplitterFactory;
import com.lrj.platform.knowledge.ingest.ContextualEnricher;
import com.lrj.platform.knowledge.lifecycle.DocumentInfo;
import com.lrj.platform.knowledge.lifecycle.DocumentTextExtractor;
import com.lrj.platform.knowledge.observability.ChunkMetrics;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;

import java.io.InputStream;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * worker 侧原文准备器：读取对象存储并完成解析、切分、上下文增强和 embedding。
 * 任一 sink 重试时会重新生成确定性的派生数据，但不会把框架对象写入任务表。
 */
public class DefaultIngestionDocumentPreparer implements IngestionDocumentPreparer {

    private final DocumentSourceStore sources;
    private final DocumentTextExtractor textExtractor;
    private final DocumentSplitterFactory splitterFactory;
    private final ContextualEnricher contextualEnricher;
    private final EmbeddingModel embeddingModel;
    private final ChunkMetrics chunkMetrics;
    private final Clock clock;

    public DefaultIngestionDocumentPreparer(
            DocumentSourceStore sources,
            DocumentTextExtractor textExtractor,
            DocumentSplitterFactory splitterFactory,
            ContextualEnricher contextualEnricher,
            EmbeddingModel embeddingModel,
            ChunkMetrics chunkMetrics,
            Clock clock
    ) {
        this.sources = Objects.requireNonNull(sources);
        this.textExtractor = Objects.requireNonNull(textExtractor);
        this.splitterFactory = Objects.requireNonNull(splitterFactory);
        this.contextualEnricher = Objects.requireNonNull(contextualEnricher);
        this.embeddingModel = Objects.requireNonNull(embeddingModel);
        this.chunkMetrics = chunkMetrics;
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public PreparedIngestionDocument prepare(IngestionJob job) throws Exception {
        String text;
        try (InputStream input = sources.open(job.tenantId(), job.source())) {
            text = textExtractor.extract(input, job.displayName());
        }

        Document document = Document.from(text);
        document.metadata()
                .put("tenantId", job.tenantId())
                .put("docId", job.documentId())
                .put("displayName", job.displayName())
                .put("file_name", job.displayName())
                .put("version", Long.toString(job.documentVersion()));
        if (job.category() != null) {
            document.metadata().put("category", job.category());
        }

        List<TextSegment> segments = splitterFactory.create().split(document);
        for (int index = 0; index < segments.size(); index++) {
            if (segments.get(index).metadata().getString("index") == null) {
                segments.get(index).metadata().put("index", Integer.toString(index));
            }
        }
        segments = contextualEnricher.enrich(text, segments);
        if (chunkMetrics != null) {
            chunkMetrics.record(splitterFactory.strategy(), 1, segments);
        }
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        DocumentInfo info = new DocumentInfo(
                job.documentId(),
                job.tenantId(),
                job.displayName(),
                job.source().contentType(),
                job.source().size(),
                segments.size(),
                Math.toIntExact(job.documentVersion()),
                clock.instant(),
                job.category());
        return new PreparedIngestionDocument(info, segments, embeddings);
    }
}
