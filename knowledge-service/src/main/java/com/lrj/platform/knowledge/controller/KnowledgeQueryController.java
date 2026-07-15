package com.lrj.platform.knowledge.controller;

import com.lrj.platform.knowledge.KnowledgeQueryService;
import com.lrj.platform.protocol.knowledge.KnowledgeHit;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryRequest;
import com.lrj.platform.protocol.knowledge.KnowledgeRuntimeView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class KnowledgeQueryController {

    /** {@code GET /rag/config} 合同版本，前端能力协商用（v2 起附带 rag 运行时视图）。 */
    private static final int CONFIG_CONTRACT_VERSION = 2;
    /** 共享库当前不支持图片入库（仅文本），前端据此禁用共享图片入口。 */
    private static final boolean SHARED_IMAGES_SUPPORTED = false;

    private final KnowledgeQueryService queryService;
    private final RagRuntimeInfo ragRuntimeInfo;

    public KnowledgeQueryController(KnowledgeQueryService queryService, RagRuntimeInfo ragRuntimeInfo) {
        this.queryService = queryService;
        this.ragRuntimeInfo = ragRuntimeInfo;
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

    /**
     * 运行时共享库状态（需认证——由边缘内部 JWT 关口把守，无需额外 scope）。前端据此决定是否展示
     * 共享库 tab / 共享图片入口。
     */
    @GetMapping("/rag/config")
    public KnowledgeRuntimeView config() {
        return new KnowledgeRuntimeView(
                CONFIG_CONTRACT_VERSION,
                queryService.publicKbEnabled(),
                SHARED_IMAGES_SUPPORTED,
                ragRuntimeInfo.view());
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
                                hit.source(),
                                visibilityOf(hit)))
                        .toList());
    }

    /** shared → {@code "public"}（共享库保留分区），否则 {@code "tenant"}（当前租户）。 */
    private static String visibilityOf(KnowledgeQueryService.Hit hit) {
        return hit.shared() ? "public" : "tenant";
    }
}
