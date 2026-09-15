package com.lrj.platform.knowledge;

import com.lrj.platform.knowledge.hybrid.KeywordSearchService;
import com.lrj.platform.knowledge.lifecycle.DocumentInfo;
import com.lrj.platform.knowledge.lifecycle.InMemoryDocumentRegistry;
import com.lrj.platform.security.TenantContext;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 新版本已写入向量但尚未提交 Registry 时，旧版本仍可查询，新版本不提前曝光。 */
class KnowledgeCommittedVersionTest {
    @Test
    void onlyCommittedVersionIsVisibleDuringPartialIngestion() {
        var model = new KnowledgeEmbeddingConfig.HashEmbeddingModel();
        var store = new InMemoryEmbeddingStore<TextSegment>();
        var registry = new InMemoryDocumentRegistry();
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
        try (var queries = new KnowledgeQueryService(store, model, mock(KeywordSearchService.class), 5, 0, false, 5)) {
            queries.setDocumentRegistry(registry);
            for (int version : new int[]{1, 2}) {
                var segment = TextSegment.from("guide version " + version);
                segment.metadata().put("tenantId", "acme").put("docId", "doc")
                        .put("index", "0").put("version", Integer.toString(version));
                store.add(model.embed(segment).content(), segment);
            }
            registry.commitVersion(info(1));
            assertThat(queries.query("guide", 5, 0.0, null).hits())
                    .extracting(KnowledgeQueryService.Hit::version).containsExactly("1");
            registry.commitVersion(info(2));
            assertThat(queries.query("guide", 5, 0.0, null).hits())
                    .extracting(KnowledgeQueryService.Hit::version).containsExactly("2");
        } finally { TenantContext.clear(); }
    }

    private DocumentInfo info(int version) {
        return new DocumentInfo("doc", "acme", "guide", "text/plain", 5, 1, version, Instant.now(), null);
    }
}
