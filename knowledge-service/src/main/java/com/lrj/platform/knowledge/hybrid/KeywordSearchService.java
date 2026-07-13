package com.lrj.platform.knowledge.hybrid;

import com.lrj.platform.knowledge.DocumentMirror;
import com.lrj.platform.security.TenantContext;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class KeywordSearchService {

    private final DocumentMirror documentMirror;
    private final KeywordTokenizer tokenizer;

    public KeywordSearchService(DocumentMirror documentMirror, KeywordTokenizer tokenizer) {
        this.documentMirror = documentMirror;
        this.tokenizer = tokenizer;
    }

    public List<KeywordHit> search(String query, int maxResults, String category) {
        return search(query, maxResults, category, null);
    }

    /**
     * @param publicTenantId 公共/共享库保留分区；非空时在隔离查当前租户分区的基础上并入该分区（读取不破坏隔离）。
     */
    public List<KeywordHit> search(String query, int maxResults, String category, String publicTenantId) {
        Set<String> queryTokens = tokenizer.tokenize(query);
        if (queryTokens.isEmpty() || maxResults <= 0) {
            return List.of();
        }
        String tenantId = TenantContext.current().tenantId();
        // Strong isolation: read only the current tenant's partition, never the global list.
        List<TextSegment> pool = new ArrayList<>(documentMirror.all(tenantId));
        if (publicTenantId != null && !publicTenantId.isBlank() && !publicTenantId.equals(tenantId)) {
            pool.addAll(documentMirror.all(publicTenantId));
        }
        return pool.stream()
                .filter(segment -> matchesCategory(segment, category))
                .map(segment -> toHit(segment, queryTokens))
                .filter(hit -> hit.score() > 0.0)
                .sorted(Comparator.comparingDouble(KeywordHit::score).reversed())
                .limit(maxResults)
                .toList();
    }

    private KeywordHit toHit(TextSegment segment, Set<String> queryTokens) {
        Set<String> segmentTokens = tokenizer.tokenize(segment.text());
        long overlap = queryTokens.stream().filter(segmentTokens::contains).count();
        double score = (double) overlap / queryTokens.size();
        return new KeywordHit(score, segment);
    }

    private static boolean matchesCategory(TextSegment segment, String category) {
        return category == null
                || category.isBlank()
                || Objects.equals(category, segment.metadata().getString("category"));
    }

    public record KeywordHit(double score, TextSegment segment) {}
}
