package com.lrj.platform.tax;

import com.lrj.platform.protocol.tax.TaxInvoiceItem;
import com.lrj.platform.protocol.tax.TaxInvoiceReviewRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaxRequestValidatorTest {

    private final TaxReviewProperties properties = new TaxReviewProperties();
    private final TaxRequestValidator validator = new TaxRequestValidator(properties);

    @Test
    void acceptsValidCnBatch() {
        ValidatedTaxInvoiceBatch batch = validator.validate(TaxFixtures.validRequest());

        assertThat(batch.jurisdiction()).isEqualTo("CN");
        assertThat(batch.taxPeriod().toString()).isEqualTo("2026-08");
        assertThat(batch.invoices()).hasSize(1);
    }

    @Test
    void rejectsMissingAndUnsupportedTopLevelFields() {
        assertCode(null, "request_required");
        assertCode(new TaxInvoiceReviewRequest("US", "2026-08", List.of(TaxFixtures.validInvoice())),
                "unsupported_jurisdiction");
        assertCode(new TaxInvoiceReviewRequest("CN", "2026/08", List.of(TaxFixtures.validInvoice())),
                "invalid_tax_period");
        assertCode(new TaxInvoiceReviewRequest("CN", "2026-08", List.of()), "invoices_required");
    }

    @Test
    void enforcesBatchLimit() {
        assertCode(new TaxInvoiceReviewRequest("CN", "2026-08",
                java.util.Collections.nCopies(101, TaxFixtures.validInvoice())), "invoice_limit_exceeded");
    }

    @Test
    void rejectsInvalidInvoiceFieldsAndAmounts() {
        TaxInvoiceItem valid = TaxFixtures.validInvoice();
        var missingNumber = new TaxInvoiceItem(valid.invoiceCode(), " ", valid.issueDate(), valid.sellerTaxId(),
                valid.buyerTaxId(), valid.netAmount(), valid.taxRate(), valid.taxAmount(), valid.totalAmount());
        assertCode(new TaxInvoiceReviewRequest("CN", "2026-08", List.of(missingNumber)), "required_field");

        var negative = new TaxInvoiceItem(valid.invoiceCode(), valid.invoiceNumber(), valid.issueDate(), valid.sellerTaxId(),
                valid.buyerTaxId(), new BigDecimal("-0.01"), valid.taxRate(), valid.taxAmount(), valid.totalAmount());
        assertCode(new TaxInvoiceReviewRequest("CN", "2026-08", List.of(negative)), "invalid_amount");

        var badRate = new TaxInvoiceItem(valid.invoiceCode(), valid.invoiceNumber(), valid.issueDate(), valid.sellerTaxId(),
                valid.buyerTaxId(), valid.netAmount(), new BigDecimal("1.01"), valid.taxAmount(), valid.totalAmount());
        assertCode(new TaxInvoiceReviewRequest("CN", "2026-08", List.of(badRate)), "invalid_tax_rate");
    }

    private void assertCode(TaxInvoiceReviewRequest request, String code) {
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TaxValidationException.class)
                .extracting(error -> ((TaxValidationException) error).code())
                .isEqualTo(code);
    }
}
