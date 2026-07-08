package com.lrj.platform.knowledge.ingest;

/**
 * (文档全文窗口, chunk 文本) → 一句上下文前缀。把生成抽成函数接口，让 {@link LlmContextualEnricher}
 * 的增强逻辑与具体后端（网关 ChatModel）解耦，从而可用确定性桩单测。
 */
@FunctionalInterface
public interface ChunkContextualizer {

    String context(String docText, String chunkText);
}
