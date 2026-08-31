package com.lrj.platform.protocol.tax;

import java.util.List;

/** 财税发票风险审查请求。首期管辖区固定为 {@code CN}，税期格式为 {@code YYYY-MM}。 */
public record TaxInvoiceReviewRequest(String jurisdiction,
                                      String taxPeriod,
                                      List<TaxInvoiceItem> invoices) {

    public TaxInvoiceReviewRequest {
        invoices = invoices == null ? null : List.copyOf(invoices);
    }
}
