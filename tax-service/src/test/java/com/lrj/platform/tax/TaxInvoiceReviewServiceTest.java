package com.lrj.platform.tax;

import com.lrj.platform.audit.AuditEventType;
import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.protocol.tax.TaxPolicyEvidence;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TaxInvoiceReviewServiceTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void returnsTrustedTenantEvidenceAndAllowlistedAuditFields() {
        TenantContext.set(new TenantContext.Tenant("tenantA", "analyst", Set.of("tax-review")));
        TaxReviewProperties properties = new TaxReviewProperties();
        AuditLogger audit = mock(AuditLogger.class);
        TaxKnowledgeClient knowledge = outcome -> List.of(
                new TaxPolicyEvidence("E1", "doc", "政策", "kb", 0.9, "正文"));
        TaxNarrator narrator = (outcome, evidence) -> new TaxNarrative("辅助说明 [E1]", "AI");
        TaxInvoiceReviewService service = new TaxInvoiceReviewService(
                new TaxRequestValidator(properties), new TaxInvoiceRuleEngine(), knowledge, narrator, audit, properties);

        var reply = service.review(TaxFixtures.validRequest());

        assertThat(reply.tenantId()).isEqualTo("tenantA");
        assertThat(reply.overallRisk()).isEqualTo("CLEAR");
        assertThat(reply.evidence()).hasSize(1);
        assertThat(reply.narrativeMode()).isEqualTo("AI");
        assertThat(reply.disclaimer()).contains("不构成正式税务意见");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> fields = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(AuditEventType.TAX_INVOICE_REVIEWED), fields.capture());
        assertThat(fields.getValue()).containsKeys("reviewId", "ruleSetVersion", "invoiceCount", "findingCount",
                "findingCodes", "overallRisk", "evidenceCount", "narrativeMode");
        assertThat(fields.getValue().toString())
                .doesNotContain("91440000111111111A", "044001900111", "113.00", "正文");
    }
}
