package com.lrj.platform.knowledge.ingest.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.knowledge.DocumentSplitterFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.LinkedHashMap;
import java.util.List;

/** 快照和原文共用 S3 客户端及权限，独立前缀使清理与容量统计不混入原文。 */
@Configuration
@EnableConfigurationProperties(IngestionPreparedProperties.class)
@ConditionalOnExpression("'${app.rag.runtime.role:combined}' == 'combined'"
        + " || '${app.rag.runtime.role:combined}' == 'ingest-worker'")
public class IngestionPreparedConfig {
    /** 只在实际执行入库的角色装配，query/ingest-api 无需模型或切分器依赖。 */
    @Bean
    IngestionPreparedStore ingestionPreparedStore(DocumentSourceProperties source, IngestionPreparedProperties prepared,
                                                  ObjectProvider<S3Client> client, ObjectMapper mapper,
                                                  DocumentSplitterFactory splitters, Environment environment) {
        DocumentSourceStore objects;
        if ("s3".equals(environment.getProperty("app.rag.source.store", "memory"))) {
            String prefix = source.getPrefix() == null ? "" : source.getPrefix().replaceAll("^/+|/+$", "");
            objects = new S3DocumentSourceStore(client.getObject(), source.getBucket(),
                    (prefix.isEmpty() ? "" : prefix + "/") + "prepared");
        } else {
            objects = new InMemoryDocumentSourceStore();
        }
        var profile = new LinkedHashMap<>(splitters.processingConfiguration());
        // 严格允许列表，禁止将 Environment 整体导出到包含用户正文的对象中。
        for (String key : List.of("app.rag.embedding.provider", "app.rag.embedding.model-name",
                "app.rag.embedding.ollama.model-name", "app.rag.contextual.enabled", "app.rag.llm.model-name",
                "app.rag.parsing.ocr.enabled", "app.rag.parsing.ocr.languages", "app.rag.parsing.ocr.dpi",
                "app.rag.parsing.ocr.min-native-chars", "app.rag.parsing.ocr.image-coverage",
                "app.rag.parsing.ocr.min-confidence", "app.rag.contextual.max-doc-chars",
                "app.rag.ingestion.resources.max-model-input-tokens", "app.rag.ingestion.resources.max-model-calls",
                "app.rag.ingestion.resources.max-contextual-calls", "app.rag.ingestion.resources.max-context-prefix-tokens",
                "app.rag.ingestion.resources.max-context-prompt-tokens", "app.rag.ingestion.resources.max-metadata-bytes")) {
            profile.put(key, environment.getProperty(key, ""));
        }
        return new ObjectStorageIngestionPreparedStore(objects, mapper, prepared, profile);
    }
}
