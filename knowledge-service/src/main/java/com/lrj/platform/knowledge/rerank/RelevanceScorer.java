package com.lrj.platform.knowledge.rerank;

/**
 * (query, 文档片段) → 相关性分（0..1）。把打分抽成函数接口，让 {@link LlmReranker} 的排序逻辑
 * 与具体后端（网关 ChatModel / 其它）解耦，从而可用确定性桩打分器单测。
 */
@FunctionalInterface
public interface RelevanceScorer {

    double score(String query, String text);
}
