package com.lrj.platform.knowledge.controller;

import com.lrj.platform.knowledge.lifecycle.ObsidianImportResult;
import com.lrj.platform.knowledge.lifecycle.ObsidianImporter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * Obsidian vault 导入端点。把一个 zip 打包的 Obsidian 库导入 RAG 知识库：
 * 每篇 {@code .md} 笔记成为一个文档、{@code [[双链]]} 成为 GraphRAG 三元组。
 *
 * <p>经 edge-gateway 的 {@code /rag/**} 路由访问，需带具备 ingest 权限的 api-key：
 * <pre>
 * curl -X POST 'http://localhost:8080/rag/obsidian/import' \
 *   -H 'X-Api-Key: dev-key-acme-ingest' \
 *   -F 'file=@my-vault.zip' -F 'category=manual'
 * </pre>
 */
@RestController
@RequestMapping("/rag/obsidian")
public class ObsidianController {

    private final ObsidianImporter importer;

    public ObsidianController(ObsidianImporter importer) {
        this.importer = importer;
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ObsidianImportResult> importVault(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String category) throws IOException {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        try (var in = file.getInputStream()) {
            return ResponseEntity.ok(importer.importVault(in, category));
        }
    }

    /** 请求体缺失/非法时的兜底提示。 */
    @PostMapping(value = "/import")
    public ResponseEntity<Map<String, String>> importVaultMissingFile() {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "multipart file (Obsidian vault zip) is required",
                "hint", "POST multipart/form-data with part 'file'=<vault>.zip"));
    }
}
