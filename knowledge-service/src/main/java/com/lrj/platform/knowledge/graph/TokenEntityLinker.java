package com.lrj.platform.knowledge.graph;

import com.lrj.platform.knowledge.hybrid.KeywordTokenizer;

import java.util.LinkedHashSet;
import java.util.Set;

public class TokenEntityLinker implements EntityLinker {

    private final GraphStore graphStore;
    private final KeywordTokenizer tokenizer;

    public TokenEntityLinker(GraphStore graphStore, KeywordTokenizer tokenizer) {
        this.graphStore = graphStore;
        this.tokenizer = tokenizer;
    }

    @Override
    public Set<String> link(String query, String tenantId, String category) {
        if (query == null || query.isBlank()) {
            return Set.of();
        }
        String normalizedQuery = query.toLowerCase();
        Set<String> queryTokens = tokenizer.tokenize(query);
        Set<String> seeds = new LinkedHashSet<>();
        for (String entity : graphStore.entities(tenantId, category)) {
            if (entity.length() < 2) {
                continue;
            }
            String normalizedEntity = entity.toLowerCase();
            if (normalizedQuery.contains(normalizedEntity)) {
                seeds.add(entity);
                continue;
            }
            Set<String> entityTokens = tokenizer.tokenize(entity);
            if (!entityTokens.isEmpty() && queryTokens.containsAll(entityTokens)) {
                seeds.add(entity);
            }
        }
        return seeds;
    }
}
