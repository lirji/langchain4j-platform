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
 * 仅尚未绑定准备结果时运行；worker 将最终结果持久化，sink 重试不再调用解析或模型。
 */
public class DefaultIngestionDocumentPreparer implements IngestionDocumentPreparer {

    private final DocumentSourceStore sources;
    private final DocumentTextExtractor textExtractor;
    private final DocumentSplitterFactory splitterFactory;
    private final ContextualEnricher contextualEnricher;
    private final EmbeddingModel embeddingModel;
    private final ChunkMetrics chunkMetrics;
    private final Clock clock;
    private final IngestionResourceProperties resources;
    private final IngestionBatcher batcher;

    public DefaultIngestionDocumentPreparer(
            DocumentSourceStore sources,
            DocumentTextExtractor textExtractor,
            DocumentSplitterFactory splitterFactory,
            ContextualEnricher contextualEnricher,
            EmbeddingModel embeddingModel,
            ChunkMetrics chunkMetrics,
            Clock clock
    ) {
        this(sources,textExtractor,splitterFactory,contextualEnricher,embeddingModel,chunkMetrics,clock,new IngestionResourceProperties());
    }

    /** 生产预算与准备阶段边界由配置提供，便捷测试构造沿用安全缺省。 */
    public DefaultIngestionDocumentPreparer(DocumentSourceStore sources, DocumentTextExtractor textExtractor,
            DocumentSplitterFactory splitterFactory, ContextualEnricher contextualEnricher, EmbeddingModel embeddingModel,
            ChunkMetrics chunkMetrics, Clock clock, IngestionResourceProperties resources) {
        this.resources = resources; this.batcher = new IngestionBatcher(resources);
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
        return prepare(job, () -> { });
    }

    /** 整文预检查和批次检查同时执行；预算截止不能被任务心跳无限延长。 */
    @Override
    public PreparedIngestionDocument prepare(IngestionJob job, Runnable leaseCheckpoint) throws Exception {
        long deadline = System.nanoTime() + resources.getPreparationTimeout().toNanos();
        Runnable checkpoint = () -> {
            leaseCheckpoint.run();
            if (System.nanoTime() > deadline) throw new IngestionResourceException(IngestionResourceError.DEADLINE);
        };
        checkpoint.run();
        com.lrj.platform.knowledge.lifecycle.ParsedDocument parsed;
        try (InputStream input = sources.open(job.tenantId(), job.source())) {
            parsed = textExtractor.parse(input, job.displayName());
        }
        String text = parsed.text();
        checkpoint.run();
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

        List<TextSegment> segments = splitterFactory.split(parsed, document.metadata());
        // 在可选上下文模型调用前预检查原文，避免明显超限文档先产生额外计费调用。
        batcher.embeddingBatches(segments);
        checkpoint.run();
        segments = contextualEnricher.enrich(text, segments, checkpoint);
        checkpoint.run();
        if (chunkMetrics != null) {
            chunkMetrics.record(splitterFactory.strategy(), 1, segments);
        }
        List<Embedding> embeddings = batcher.embed(embeddingModel,segments,checkpoint);
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
