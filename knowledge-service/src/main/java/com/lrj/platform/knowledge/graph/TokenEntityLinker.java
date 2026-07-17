package com.lrj.platform.knowledge.graph;

import com.lrj.platform.knowledge.hybrid.KeywordTokenizer;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@link EntityLinker} 的默认实现：从 {@link GraphStore} 取出租户/类目下的实体，凡查询串包含实体表面词
 * （子串命中），或经 {@link KeywordTokenizer} 分词后实体 token 全部落在查询 token 内的，即作为种子实体返回。
 */
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
