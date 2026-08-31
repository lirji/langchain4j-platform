package com.lrj.platform.tax;

import com.lrj.platform.protocol.tax.TaxInvoiceItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaxInvoiceRuleEngineTest {

    private final TaxInvoiceRuleEngine rules = new TaxInvoiceRuleEngine();

    @Test
    void consistentInvoiceIsClear() {
        TaxReviewOutcome outcome = rules.review(batch(List.of(TaxFixtures.validInvoice())));

        assertThat(outcome.overallRisk()).isEqualTo("CLEAR");
        assertThat(outcome.findings()).isEmpty();
    }

    @Test
    void detectsDuplicateAmountTaxAndPeriodRisks() {
        TaxInvoiceItem valid = TaxFixtures.validInvoice();
        TaxInvoiceItem riskyDuplicate = new TaxInvoiceItem(
                valid.invoiceCode(), valid.invoiceNumber(), LocalDate.of(2026, 7, 31),
                valid.sellerTaxId(), valid.buyerTaxId(),
                new BigDecimal("100.00"), new BigDecimal("0.13"),
                new BigDecimal("12.00"), new BigDecimal("120.00"));

        TaxReviewOutcome outcome = rules.review(batch(List.of(valid, riskyDuplicate)));

        assertThat(outcome.overallRisk()).isEqualTo("HIGH");
        assertThat(outcome.findings()).extracting(item -> item.code()).containsExactly(
                TaxInvoiceRuleEngine.DUPLICATE_INVOICE,
                TaxInvoiceRuleEngine.TOTAL_AMOUNT_MISMATCH,
                TaxInvoiceRuleEngine.TAX_AMOUNT_MISMATCH,
                TaxInvoiceRuleEngine.OUTSIDE_TAX_PERIOD);
    }

    @Test
    void allowsOneCentRoundingTolerance() {
        TaxInvoiceItem valid = TaxFixtures.validInvoice();
        TaxInvoiceItem rounded = new TaxInvoiceItem(
                valid.invoiceCode(), "00000002", valid.issueDate(), valid.sellerTaxId(), valid.buyerTaxId(),
                valid.netAmount(), valid.taxRate(), new BigDecimal("13.01"), new BigDecimal("113.01"));

        TaxReviewOutcome outcome = rules.review(batch(List.of(rounded)));

        assertThat(outcome.findings()).isEmpty();
    }

    private static ValidatedTaxInvoiceBatch batch(List<TaxInvoiceItem> invoices) {
        return new ValidatedTaxInvoiceBatch("CN", YearMonth.of(2026, 8), invoices);
    }
}
