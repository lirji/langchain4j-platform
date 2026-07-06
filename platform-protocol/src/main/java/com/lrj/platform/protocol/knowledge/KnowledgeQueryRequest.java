package com.lrj.platform.protocol.knowledge;

public record KnowledgeQueryRequest(String query, Integer topK, Double minScore, String category) {
}
