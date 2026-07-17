package com.lrj.platform.knowledge;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 入库切分器工厂：按 {@code app.rag.chunking.*} 配置构建 langchain4j 的 {@link DocumentSplitter}。
 * 支持 recursive / markdown-header / parent-child / semantic 四种策略，字符或 token 计量（token 模式用
 * {@link OpenAiTokenCountEstimator}），语义切分复用注入的 {@link EmbeddingModel}。由 {@code DocumentService}
 * 在文档入库时调用 {@link #create()} 取得切分器。
 */
@Component
public class DocumentSplitterFactory {

    private static final Logger log = LoggerFactory.getLogger(DocumentSplitterFactory.class);

    private final String strategy;
    private final String unit;
    private final int maxSize;
    private final int overlap;
    private final int minSectionSize;
    private final String tokenizerModel;
    private final String parentStrategy;
    private final int parentSize;
    private final int parentOverlap;
    private final int semanticBufferSize;
    private final double semanticPercentile;
    private final int semanticMaxSize;
    private final int semanticMinSize;
    private final EmbeddingModel embeddingModel;

    public DocumentSplitterFactory(
            @Value("${app.rag.chunking.strategy:recursive}") String strategy,
            @Value("${app.rag.chunking.unit:chars}") String unit,
            @Value("${app.rag.chunking.max-size:${app.rag.chunking.max-chars:300}}") int maxSize,
            @Value("${app.rag.chunking.overlap:50}") int overlap,
            @Value("${app.rag.chunking.min-section-size:0}") int minSectionSize,
            @Value("${app.rag.chunking.tokenizer-model:gpt-4o-mini}") String tokenizerModel,
            @Value("${app.rag.chunking.parent.strategy:recursive}") String parentStrategy,
            @Value("${app.rag.chunking.parent.size:1200}") int parentSize,
            @Value("${app.rag.chunking.parent.overlap:0}") int parentOverlap,
            @Value("${app.rag.chunking.semantic.buffer-size:1}") int semanticBufferSize,
            @Value("${app.rag.chunking.semantic.breakpoint-percentile:95}") double semanticPercentile,
            @Value("${app.rag.chunking.semantic.max-size:1000}") int semanticMaxSize,
            @Value("${app.rag.chunking.semantic.min-size:0}") int semanticMinSize,
            EmbeddingModel embeddingModel) {
        this.strategy = strategy == null ? "recursive" : strategy.trim().toLowerCase();
        this.unit = unit == null ? "chars" : unit.trim().toLowerCase();
        this.maxSize = maxSize;
        this.overlap = overlap;
        this.minSectionSize = Math.max(0, minSectionSize);
        this.tokenizerModel = tokenizerModel;
        this.parentStrategy = parentStrategy == null ? "recursive" : parentStrategy.trim().toLowerCase();
        this.parentSize = parentSize;
        this.parentOverlap = parentOverlap;
        this.semanticBufferSize = semanticBufferSize;
        this.semanticPercentile = semanticPercentile;
        this.semanticMaxSize = semanticMaxSize;
        this.semanticMinSize = semanticMinSize;
        this.embeddingModel = embeddingModel;
    }

    public DocumentSplitter create() {
        boolean tokenMode = "tokens".equals(unit);
        TokenCountEstimator estimator = tokenMode ? new OpenAiTokenCountEstimator(tokenizerModel) : null;
        DocumentSplitter recursive = tokenMode
                ? DocumentSplitters.recursive(maxSize, overlap, estimator)
                : DocumentSplitters.recursive(maxSize, overlap);

        DocumentSplitter splitter = switch (strategy) {
            case "recursive" -> recursive;
            case "markdown-header" -> new MarkdownHeaderSplitter(maxSize, recursive, estimator, minSectionSize);
            case "parent-child" -> new ParentChildSplitter(buildParentSplitter(estimator), recursive);
            case "semantic" -> buildSemanticSplitter(estimator);
            default -> {
                log.warn("Unknown app.rag.chunking.strategy '{}', falling back to recursive", strategy);
                yield recursive;
            }
        };
        log.info("Knowledge chunking: strategy={} unit={} max-size={} overlap={}",
                strategy, unit, maxSize, overlap);
        return splitter;
    }

    private DocumentSplitter buildSemanticSplitter(TokenCountEstimator estimator) {
        DocumentSplitter fallback = estimator != null
                ? DocumentSplitters.recursive(semanticMaxSize, overlap, estimator)
                : DocumentSplitters.recursive(semanticMaxSize, overlap);
        return new SemanticChunkingSplitter(embeddingModel, semanticBufferSize, semanticPercentile,
                semanticMaxSize, semanticMinSize, fallback, estimator);
    }

    private DocumentSplitter buildParentSplitter(TokenCountEstimator estimator) {
        DocumentSplitter parentRecursive = estimator != null
                ? DocumentSplitters.recursive(parentSize, parentOverlap, estimator)
                : DocumentSplitters.recursive(parentSize, parentOverlap);
        return switch (parentStrategy) {
            case "recursive" -> parentRecursive;
            case "markdown-header" -> new MarkdownHeaderSplitter(parentSize, parentRecursive, estimator, minSectionSize);
            default -> {
                log.warn("Unknown app.rag.chunking.parent.strategy '{}', falling back to recursive", parentStrategy);
                yield parentRecursive;
            }
        };
    }

    public String strategy() {
        return strategy;
    }
}
