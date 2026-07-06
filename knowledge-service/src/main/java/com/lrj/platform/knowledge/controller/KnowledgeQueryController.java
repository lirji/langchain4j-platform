package com.lrj.platform.knowledge.controller;

import com.lrj.platform.knowledge.KnowledgeQueryService;
import com.lrj.platform.protocol.knowledge.KnowledgeHit;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class KnowledgeQueryController {

    private final KnowledgeQueryService queryService;

    public KnowledgeQueryController(KnowledgeQueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping({"/rag/query", "/knowledge/query"})
    public ResponseEntity<KnowledgeQueryReply> query(@RequestBody KnowledgeQueryRequest request) {
        try {
            KnowledgeQueryService.QueryResult result = queryService.query(
                    request.query(),
                    request.topK(),
                    request.minScore(),
                    request.category());
            return ResponseEntity.ok(toReply(result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().header("X-Error", e.getMessage()).build();
        }
    }

    private static KnowledgeQueryReply toReply(KnowledgeQueryService.QueryResult result) {
        return new KnowledgeQueryReply(
                result.query(),
                result.tenantId(),
                result.hits().stream()
                        .map(hit -> new KnowledgeHit(
                                hit.id(),
                                hit.score(),
                                hit.docId(),
                                hit.displayName(),
                                hit.category(),
                                hit.index(),
                                hit.text(),
                                hit.source()))
                        .toList());
    }
}
