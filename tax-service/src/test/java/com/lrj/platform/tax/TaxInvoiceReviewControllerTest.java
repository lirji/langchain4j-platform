package com.lrj.platform.tax;

import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.protocol.tax.TaxInvoiceReviewReply;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TaxInvoiceReviewControllerTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void rejectsCallerWithoutTaxReviewScopeBeforeServiceCall() {
        TenantContext.set(new TenantContext.Tenant("tenantA", "viewer", Set.of("chat")));
        TaxInvoiceReviewService service = mock(TaxInvoiceReviewService.class);
        TaxInvoiceReviewController controller = new TaxInvoiceReviewController(service);

        assertThatThrownBy(() -> controller.review(TaxFixtures.validRequest()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void endpointReturnsReviewForAuthorizedCaller() throws Exception {
        TenantContext.set(new TenantContext.Tenant("tenantA", "analyst", Set.of("tax-review")));
        TaxInvoiceReviewService service = mock(TaxInvoiceReviewService.class);
        when(service.review(org.mockito.ArgumentMatchers.any())).thenReturn(new TaxInvoiceReviewReply(
                "review-1", "tenantA", "v1", "CLEAR", 1, 0, List.of(), List.of(),
                "未发现异常", "FALLBACK", TaxInvoiceReviewService.DISCLAIMER));

        standaloneSetup(new TaxInvoiceReviewController(service)).build()
                .perform(post("/tax/invoices/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jurisdiction":"CN","taxPeriod":"2026-08","invoices":[{
                                  "invoiceCode":"044001900111","invoiceNumber":"00000001","issueDate":"2026-08-15",
                                  "sellerTaxId":"91440000111111111A","buyerTaxId":"91440000222222222B",
                                  "netAmount":100.00,"taxRate":0.13,"taxAmount":13.00,"totalAmount":113.00
                                }]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("tenantA"))
                .andExpect(jsonPath("$.overallRisk").value("CLEAR"))
                .andExpect(jsonPath("$.narrativeMode").value("FALLBACK"));
    }

    @Test
    void validationErrorsUseStableBadRequestContract() throws Exception {
        TenantContext.set(new TenantContext.Tenant("tenantA", "analyst", Set.of("tax-review")));
        TaxReviewProperties properties = new TaxReviewProperties();
        TaxInvoiceReviewService service = new TaxInvoiceReviewService(
                new TaxRequestValidator(properties), new TaxInvoiceRuleEngine(), outcome -> List.of(),
                new DeterministicTaxNarrator(), mock(AuditLogger.class), properties);

        standaloneSetup(new TaxInvoiceReviewController(service))
                .setControllerAdvice(new TaxExceptionHandler())
                .build()
                .perform(post("/tax/invoices/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jurisdiction\":\"CN\",\"taxPeriod\":\"2026/08\",\"invoices\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_tax_period"));
    }
}
