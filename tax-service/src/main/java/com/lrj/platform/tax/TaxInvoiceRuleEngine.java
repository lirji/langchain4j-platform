package com.lrj.platform.tax;

import com.lrj.platform.protocol.tax.TaxInvoiceItem;
import com.lrj.platform.protocol.tax.TaxRiskFinding;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 四条可重复的发票内部一致性规则。这里不判断真伪、抵扣资格或法定税率。 */
@Component
public class TaxInvoiceRuleEngine {

    static final String DUPLICATE_INVOICE = "DUPLICATE_INVOICE";
    static final String TOTAL_AMOUNT_MISMATCH = "TOTAL_AMOUNT_MISMATCH";
    static final String TAX_AMOUNT_MISMATCH = "TAX_AMOUNT_MISMATCH";
    static final String OUTSIDE_TAX_PERIOD = "OUTSIDE_TAX_PERIOD";
    private static final BigDecimal CENT = new BigDecimal("0.01");

    public TaxReviewOutcome review(ValidatedTaxInvoiceBatch batch) {
        List<TaxRiskFinding> findings = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TaxInvoiceItem invoice : batch.invoices()) {
            String reference = invoiceRef(invoice);
            String duplicateKey = invoice.invoiceCode().strip() + "\u0000" + invoice.invoiceNumber().strip();
            if (!seen.add(duplicateKey)) {
                findings.add(finding(DUPLICATE_INVOICE, "HIGH", reference, "批次内发票代码和号码重复"));
            }

            BigDecimal expectedTotal = invoice.netAmount().add(invoice.taxAmount()).setScale(2, RoundingMode.HALF_UP);
            if (difference(expectedTotal, invoice.totalAmount()) > 0) {
                findings.add(finding(TOTAL_AMOUNT_MISMATCH, "HIGH", reference,
                        "价税合计与未税金额加税额不一致，期望 " + expectedTotal.toPlainString()
                                + "，实际 " + invoice.totalAmount().toPlainString()));
            }

            BigDecimal expectedTax = invoice.netAmount().multiply(invoice.taxRate()).setScale(2, RoundingMode.HALF_UP);
            if (difference(expectedTax, invoice.taxAmount()) > 0) {
                findings.add(finding(TAX_AMOUNT_MISMATCH, "MEDIUM", reference,
                        "税额与未税金额乘税率不一致，期望 " + expectedTax.toPlainString()
                                + "，实际 " + invoice.taxAmount().toPlainString()));
            }

            if (!batch.taxPeriod().equals(java.time.YearMonth.from(invoice.issueDate()))) {
                findings.add(finding(OUTSIDE_TAX_PERIOD, "MEDIUM", reference,
                        "开票日期不属于申报税期 " + batch.taxPeriod()));
            }
        }
        return new TaxReviewOutcome(overallRisk(findings), findings);
    }

    private static int difference(BigDecimal expected, BigDecimal actual) {
        return expected.subtract(actual).abs().compareTo(CENT);
    }

    private static String invoiceRef(TaxInvoiceItem invoice) {
        return invoice.invoiceCode().strip() + "-" + invoice.invoiceNumber().strip();
    }

    private static TaxRiskFinding finding(String code, String severity, String reference, String message) {
        return new TaxRiskFinding(code, severity, reference, message);
    }

    private static String overallRisk(List<TaxRiskFinding> findings) {
        if (findings.stream().anyMatch(item -> "HIGH".equals(item.severity()))) return "HIGH";
        if (!findings.isEmpty()) return "MEDIUM";
        return "CLEAR";
    }
}
