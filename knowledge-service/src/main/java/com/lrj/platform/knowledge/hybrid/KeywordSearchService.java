package com.lrj.platform.knowledge.hybrid;

import com.lrj.platform.knowledge.DocumentMirror;
import com.lrj.platform.security.TenantContext;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Service;

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
        Set<String> queryTokens = tokenizer.tokenize(query);
        if (queryTokens.isEmpty() || maxResults <= 0) {
            return List.of();
        }
        String tenantId = TenantContext.current().tenantId();
        return documentMirror.all().stream()
                .filter(segment -> belongsToTenant(segment, tenantId))
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

    private static boolean belongsToTenant(TextSegment segment, String tenantId) {
        return segment != null
                && segment.metadata() != null
                && Objects.equals(tenantId, segment.metadata().getString("tenantId"));
    }

    private static boolean matchesCategory(TextSegment segment, String category) {
        return category == null
                || category.isBlank()
                || Objects.equals(category, segment.metadata().getString("category"));
    }

    public record KeywordHit(double score, TextSegment segment) {}
}
