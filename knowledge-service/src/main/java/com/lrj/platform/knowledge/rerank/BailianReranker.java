package com.lrj.platform.knowledge.rerank;

import com.lrj.platform.knowledge.KnowledgeQueryService.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 百炼 {@code qwen3-rerank} 批量重排器。外部服务失败时 fail-open，退回融合检索的初始顺序。
 */
public class BailianReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(BailianReranker.class);

    private final BailianRerankClient client;
    private final int multiplier;
    private final double minScore;
    private final int maxDocuments;

    BailianReranker(BailianRerankClient client, int multiplier, double minScore, int maxDocuments) {
        this.client = client;
        this.multiplier = Math.max(1, multiplier);
        this.minScore = Math.max(0.0, Math.min(1.0, minScore));
        this.maxDocuments = Math.max(1, Math.min(500, maxDocuments));
    }

    @Override
    public int retrieveMultiplier() {
        return multiplier;
    }

    @Override
    public List<Hit> rerank(String query, List<Hit> candidates, int topK) {
        if (candidates.isEmpty() || topK <= 0) {
            return List.of();
        }
        int requestSize = Math.min(candidates.size(), maxDocuments);
        List<Hit> requestedCandidates = candidates.subList(0, requestSize);
        List<String> documents = requestedCandidates.stream()
                .map(hit -> hit.text() == null ? "" : hit.text())
                .toList();

        List<BailianRerankClient.RankedResult> ranked;
        try {
            ranked = client.rerank(query, documents, Math.min(topK, requestSize));
        } catch (RuntimeException e) {
            log.warn("Bailian rerank failed; preserving initial retrieval order: {}", e.toString());
            return initialTopK(candidates, topK);
        }

        boolean[] seen = new boolean[requestSize];
        List<Hit> output = new ArrayList<>(Math.min(topK, ranked.size()));
        for (BailianRerankClient.RankedResult result : ranked) {
            int index = result.index();
            if (index < 0 || index >= requestSize || seen[index]) {
                continue;
            }
            seen[index] = true;
            if (result.score() >= minScore) {
                output.add(requestedCandidates.get(index));
                if (output.size() == topK) {
                    break;
                }
            }
        }
        return List.copyOf(output);
    }

    private static List<Hit> initialTopK(List<Hit> candidates, int topK) {
        return candidates.size() <= topK
                ? List.copyOf(candidates)
                : List.copyOf(candidates.subList(0, topK));
    }
}
