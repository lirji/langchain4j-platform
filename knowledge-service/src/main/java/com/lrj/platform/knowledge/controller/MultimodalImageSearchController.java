package com.lrj.platform.knowledge.controller;

import com.lrj.platform.knowledge.authz.AuthzMode;
import com.lrj.platform.knowledge.authz.KnowledgeAuthz;
import com.lrj.platform.knowledge.authz.NoopKnowledgeAuthz;
import com.lrj.platform.knowledge.multimodal.MultimodalEmbeddingProperties;
import com.lrj.platform.knowledge.multimodal.MultimodalRetrievalService;
import com.lrj.platform.security.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 原生多模态检索入口（{@code app.rag.multimodal-embedding.enabled=true} 才映射）。走与其它 {@code /rag/**}
 * 同一套鉴权链（内部 JWT + 多租户 + 限流）。
 *
 * <ul>
 *   <li>{@code POST /rag/image} — multipart 上传图片，原生 CLIP embed 入库（type=image，需 ingest scope）</li>
 *   <li>{@code POST /rag/image-search} — 文本 query 检索图片（text→image）</li>
 * </ul>
 */
@RestController
@ConditionalOnProperty(name = "app.rag.multimodal-embedding.enabled", havingValue = "true")
public class MultimodalImageSearchController {

    private final MultimodalRetrievalService retrieval;
    private final MultimodalEmbeddingProperties props;
    // 细粒度授权：默认 Noop；enforce 时图片检索 fail-closed（图片无 docId/owner，不在本期文本文档 ReBAC 范围）。
    private KnowledgeAuthz knowledgeAuthz = new NoopKnowledgeAuthz();

    public MultimodalImageSearchController(MultimodalRetrievalService retrieval, MultimodalEmbeddingProperties props) {
        this.retrieval = retrieval;
        this.props = props;
    }

    @Autowired(required = false)
    public void setKnowledgeAuthz(KnowledgeAuthz knowledgeAuthz) {
        if (knowledgeAuthz != null) {
            this.knowledgeAuthz = knowledgeAuthz;
        }
    }

    /** 图片入库：multipart {@code image}。返回 {@code {id, fileName, type}}。需 ingest scope。 */
    @PostMapping(value = "/rag/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> ingest(@RequestPart("image") MultipartFile image) throws IOException {
        requireIngest();
        if (image == null || image.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "empty image"));
        }
        if (image.getSize() > props.getMaxImageBytes()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "image too large: " + image.getSize() + " > " + props.getMaxImageBytes() + " bytes"));
        }
        String fileName = image.getOriginalFilename();
        try {
            String id = retrieval.ingestImage(image.getBytes(), image.getContentType(), fileName);
            return ResponseEntity.ok(Map.of("id", id, "fileName", fileName == null ? "" : fileName, "type", "image"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** text→image：body {@code {query, topK?, minScore?}} → {@code {query, results:[{id,fileName,score}]}}。 */
    @PostMapping(value = "/rag/image-search",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> search(@RequestBody SearchRequest body) {
        if (knowledgeAuthz.mode() == AuthzMode.ENFORCE) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "image search lacks resource-level ReBAC (no docId/owner); disabled under enforce mode"));
        }
        if (body == null || body.query() == null || body.query().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "empty query"));
        }
        int topK = body.topK() == null ? 0 : body.topK();
        double minScore = body.minScore() == null ? -1 : body.minScore();
        List<MultimodalRetrievalService.ImageMatch> results =
                retrieval.searchByText(body.query(), topK, minScore);
        return ResponseEntity.ok(Map.of("query", body.query(), "results", results));
    }

    private static void requireIngest() {
        if (!TenantContext.current().hasScope("ingest")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ingest scope required");
        }
    }

    /** image-search 的 JSON body 绑定。 */
    public record SearchRequest(String query, Integer topK, Double minScore) {}
}
