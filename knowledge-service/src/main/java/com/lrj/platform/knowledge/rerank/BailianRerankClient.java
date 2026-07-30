package com.lrj.platform.knowledge.rerank;

import java.util.List;

/**
 * 阿里云百炼文本重排 API 边界。实现返回 API 已按相关性降序排列的候选索引与分数。
 */
@FunctionalInterface
interface BailianRerankClient {

    List<RankedResult> rerank(String query, List<String> documents, int topN);

    record RankedResult(int index, double score) {
    }
}
