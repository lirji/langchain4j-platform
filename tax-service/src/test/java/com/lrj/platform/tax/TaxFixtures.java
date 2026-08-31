package com.lrj.platform.tax;

import com.lrj.platform.protocol.tax.TaxInvoiceItem;
import com.lrj.platform.protocol.tax.TaxInvoiceReviewRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

final class TaxFixtures {

    private TaxFixtures() {
    }

    static TaxInvoiceItem validInvoice() {
        return new TaxInvoiceItem(
                "044001900111",
                "00000001",
                LocalDate.of(2026, 8, 15),
                "91440000111111111A",
                "91440000222222222B",
                new BigDecimal("100.00"),
                new BigDecimal("0.13"),
                new BigDecimal("13.00"),
                new BigDecimal("113.00"));
    }

    static TaxInvoiceReviewRequest validRequest() {
        return new TaxInvoiceReviewRequest("CN", "2026-08", List.of(validInvoice()));
    }
}
