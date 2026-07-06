package com.lrj.platform.protocol.knowledge;

import java.util.List;

public record KnowledgeQueryReply(String query, String tenantId, List<KnowledgeHit> hits) {

    public KnowledgeQueryReply {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }
}
