package com.lrj.platform.knowledge.rerank;

import com.lrj.platform.knowledge.KnowledgeQueryService.Hit;

import java.util.List;

/**
 * 默认重排器：不改顺序，直接取候选前 {@code topK}（候选已按初始分降序）。等价于「不 rerank」。
 */
public class NoopReranker implements Reranker {

    @Override
    public List<Hit> rerank(String query, List<Hit> candidates, int topK) {
        if (candidates.size() <= topK) {
            return candidates;
        }
        return List.copyOf(candidates.subList(0, topK));
    }
}
