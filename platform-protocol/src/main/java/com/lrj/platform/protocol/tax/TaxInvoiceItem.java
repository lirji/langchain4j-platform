package com.lrj.platform.protocol.tax;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 一张待审查的结构化增值税发票。金额使用 {@link BigDecimal}，避免浮点误差影响勾稽结果。
 */
public record TaxInvoiceItem(String invoiceCode,
                             String invoiceNumber,
                             LocalDate issueDate,
                             String sellerTaxId,
                             String buyerTaxId,
                             BigDecimal netAmount,
                             BigDecimal taxRate,
                             BigDecimal taxAmount,
                             BigDecimal totalAmount) {
}
