package com.lrj.platform.knowledge.hybrid;

import com.lrj.platform.knowledge.DocumentMirror;
import com.lrj.platform.security.TenantContext;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KeywordSearchServiceTest：验证 {@link KeywordSearchService} 基于 {@link DocumentMirror} 的关键词检索
 * 按租户与 category 隔离结果，并验证 {@link SimpleKeywordTokenizer} 对中文按二元组（bigram）切词。
 * 依赖 {@link TenantContext}，用例后在 {@code @AfterEach} 清理。
 */
class KeywordSearchServiceTest {

    private final DocumentMirror mirror = new DocumentMirror();
    private final KeywordSearchService service = new KeywordSearchService(mirror, new SimpleKeywordTokenizer());

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void search_isTenantAndCategoryScoped() {
        mirror.add(List.of(
                segment("acme", "doc-1", "manual", "refund keyword phoenix"),
                segment("acme", "doc-2", "faq", "refund keyword phoenix"),
                segment("globex", "doc-3", "manual", "refund keyword phoenix")));
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));

        var hits = service.search("phoenix refund", 10, "manual");

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().segment().metadata().getString("docId")).isEqualTo("doc-1");
        assertThat(hits.getFirst().score()).isGreaterThan(0.0);
    }

    @Test
    void tokenizer_supportsChineseBigrams() {
        SimpleKeywordTokenizer tokenizer = new SimpleKeywordTokenizer();

        assertThat(tokenizer.tokenize("知识库文档")).contains("知识", "识库", "库文", "文档");
    }

    private static TextSegment segment(String tenantId, String docId, String category, String text) {
        Metadata metadata = new Metadata()
                .put("tenantId", tenantId)
                .put("docId", docId)
                .put("displayName", docId + ".md")
                .put("index", "0");
        if (category != null) {
            metadata.put("category", category);
        }
        return TextSegment.from(text, metadata);
    }
}
