package com.lrj.platform.tax;

import com.lrj.platform.protocol.knowledge.KnowledgeHit;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpTaxKnowledgeClientTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void mapsCurrentTenantEvidenceAndBoundsExcerpt() {
        TenantContext.set(new TenantContext.Tenant("tenantA", "analyst", Set.of("tax-review")));
        RestTemplate restTemplate = mock(RestTemplate.class);
        var hit = new KnowledgeHit("h1", 0.8, "doc-1", "政策说明", "tax-policy", "0",
                "123456789", "kb", "tenant");
        when(restTemplate.postForObject(eq("/rag/query"), any(), eq(KnowledgeQueryReply.class)))
                .thenReturn(new KnowledgeQueryReply("q", "tenantA", List.of(hit)));
        TaxReviewProperties properties = new TaxReviewProperties();
        properties.getKnowledge().setEvidenceMaxChars(5);
        HttpTaxKnowledgeClient client = new HttpTaxKnowledgeClient(restTemplate, properties.getKnowledge());

        var evidence = client.findEvidence(new TaxReviewOutcome("CLEAR", List.of()));

        assertThat(evidence).singleElement().satisfies(item -> {
            assertThat(item.citationId()).isEqualTo("E1");
            assertThat(item.docId()).isEqualTo("doc-1");
            assertThat(item.excerpt()).isEqualTo("12345…");
        });
    }

    @Test
    void discardsEvidenceWhenReplyTenantDoesNotMatch() {
        TenantContext.set(new TenantContext.Tenant("tenantA", "analyst", Set.of("tax-review")));
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForObject(eq("/rag/query"), any(), eq(KnowledgeQueryReply.class)))
                .thenReturn(new KnowledgeQueryReply("q", "tenantB", List.of(
                        new KnowledgeHit("h1", 0.8, "doc", "name", "tax-policy", "0", "text", "kb", "tenant"))));
        HttpTaxKnowledgeClient client = new HttpTaxKnowledgeClient(restTemplate, new TaxReviewProperties().getKnowledge());

        assertThat(client.findEvidence(new TaxReviewOutcome("CLEAR", List.of()))).isEmpty();
    }

    @Test
    void knowledgeFailureFallsBackToEmptyEvidence() {
        TenantContext.set(new TenantContext.Tenant("tenantA", "analyst", Set.of("tax-review")));
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForObject(eq("/rag/query"), any(), eq(KnowledgeQueryReply.class)))
                .thenThrow(new IllegalStateException("knowledge unavailable"));
        HttpTaxKnowledgeClient client = new HttpTaxKnowledgeClient(restTemplate, new TaxReviewProperties().getKnowledge());

        assertThat(client.findEvidence(new TaxReviewOutcome("HIGH", List.of()))).isEmpty();
    }
}
