package com.lrj.platform.tax;

import com.lrj.platform.protocol.tax.TaxInvoiceItem;

import java.time.YearMonth;
import java.util.List;

/** 已通过结构校验的不可变发票批次。 */
record ValidatedTaxInvoiceBatch(String jurisdiction,
                                YearMonth taxPeriod,
                                List<TaxInvoiceItem> invoices) {

    ValidatedTaxInvoiceBatch {
        invoices = List.copyOf(invoices);
    }
}
