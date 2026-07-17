package com.lrj.platform.knowledge.graph;

/**
 * GraphRAG 三元组抽取接口：从一段文本抽出 (主语,谓语,宾语) 三元组，产出 {@link ExtractedTriples}。
 * 默认实现为规则式的 {@code RuleBasedGraphExtractor}，由 {@link GraphIngestor} 在入库时调用。
 */
public interface GraphExtractor {

    ExtractedTriples extract(String text);
}
