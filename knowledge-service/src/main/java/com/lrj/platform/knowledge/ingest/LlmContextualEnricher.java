package com.lrj.platform.knowledge.ingest;

import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM Contextual Retrieval 增强：对每个 chunk 用文档全文窗口生成一句上下文前缀，拼到 chunk 前再嵌入。
 * 生成经 {@link ChunkContextualizer} 抽象注入，逻辑可确定性单测；单 chunk 生成失败保留原文（不崩入库）。
 */
public class LlmContextualEnricher implements ContextualEnricher {

    private final ChunkContextualizer contextualizer;
    private final int maxDocChars;

    public LlmContextualEnricher(ChunkContextualizer contextualizer, int maxDocChars) {
        this.contextualizer = contextualizer;
        this.maxDocChars = Math.max(256, maxDocChars);
    }

    @Override
    public List<TextSegment> enrich(String docText, List<TextSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return segments;
        }
        String docWindow = truncate(docText, maxDocChars);
        List<TextSegment> out = new ArrayList<>(segments.size());
        for (TextSegment seg : segments) {
            String prefix = safeContext(docWindow, seg.text());
            if (prefix == null || prefix.isBlank()) {
                out.add(seg);
            } else {
                String enriched = prefix.trim() + "\n\n" + seg.text();
                out.add(TextSegment.from(enriched, seg.metadata()));
            }
        }
        return out;
    }

    private String safeContext(String docWindow, String chunkText) {
        try {
            return contextualizer.context(docWindow, chunkText);
        } catch (RuntimeException e) {
            return null; // 单 chunk 上下文生成失败：保留原文
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
