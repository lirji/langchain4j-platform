package com.lrj.platform.knowledge.ingest;

import dev.langchain4j.data.segment.TextSegment;

import java.util.List;

/**
 * Contextual Retrieval 入库增强 SPI（移植单体 {@code ContextualEnricher} / Anthropic 那套）：
 * 入库时给每个 chunk 生成一句「安放回全文」的上下文前缀（消解代词/缩写、点明位置）再嵌入，
 * 使 chunk 自洽、显著降低召回失败率。与切分策略正交。
 *
 * <p>默认 {@link NoopContextualEnricher}（不改 chunk）；开启后每 chunk 一次 LLM 调用（失败保留原文）。
 */
public interface ContextualEnricher {

    /** 返回增强后的 segments（数量、顺序、metadata 保持不变，仅 text 可能被加前缀）。 */
    List<TextSegment> enrich(String docText, List<TextSegment> segments);
}
