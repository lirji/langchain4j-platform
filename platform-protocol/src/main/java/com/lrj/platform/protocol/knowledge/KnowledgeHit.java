package com.lrj.platform.protocol.knowledge;

public record KnowledgeHit(String id,
                           Double score,
                           String docId,
                           String displayName,
                           String category,
                           String index,
                           String text,
                           String source) {
}
