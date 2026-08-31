package com.lrj.platform.tax;

import com.lrj.platform.protocol.tax.TaxInvoiceItem;
import com.lrj.platform.protocol.tax.TaxInvoiceReviewRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/** 对请求结构和资源边界做 fail-closed 校验，不把非法输入伪装成业务风险。 */
@Component
public class TaxRequestValidator {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");
    private final TaxReviewProperties properties;

    public TaxRequestValidator(TaxReviewProperties properties) {
        this.properties = properties;
    }

    public ValidatedTaxInvoiceBatch validate(TaxInvoiceReviewRequest request) {
        if (request == null) throw invalid("request_required", "请求体不能为空");
        if (!"CN".equals(request.jurisdiction())) {
            throw invalid("unsupported_jurisdiction", "jurisdiction 当前仅支持 CN");
        }
        YearMonth period;
        try {
            period = YearMonth.parse(request.taxPeriod());
        } catch (DateTimeParseException | NullPointerException ex) {
            throw invalid("invalid_tax_period", "taxPeriod 必须使用 YYYY-MM 格式");
        }
        if (request.invoices() == null || request.invoices().isEmpty()) {
            throw invalid("invoices_required", "invoices 至少包含一张发票");
        }
        if (request.invoices().size() > properties.getMaxInvoices()) {
            throw invalid("invoice_limit_exceeded", "单次最多审查 " + properties.getMaxInvoices() + " 张发票");
        }
        for (int index = 0; index < request.invoices().size(); index++) {
            validateInvoice(request.invoices().get(index), index);
        }
        return new ValidatedTaxInvoiceBatch(request.jurisdiction(), period, request.invoices());
    }

    private static void validateInvoice(TaxInvoiceItem invoice, int index) {
        String prefix = "invoices[" + index + "]";
        if (invoice == null) throw invalid("invoice_required", prefix + " 不能为空");
        requireText(invoice.invoiceCode(), 32, prefix + ".invoiceCode");
        requireText(invoice.invoiceNumber(), 32, prefix + ".invoiceNumber");
        requireText(invoice.sellerTaxId(), 64, prefix + ".sellerTaxId");
        requireText(invoice.buyerTaxId(), 64, prefix + ".buyerTaxId");
        if (invoice.issueDate() == null) throw invalid("issue_date_required", prefix + ".issueDate 不能为空");
        requireAmount(invoice.netAmount(), prefix + ".netAmount");
        requireAmount(invoice.taxAmount(), prefix + ".taxAmount");
        requireAmount(invoice.totalAmount(), prefix + ".totalAmount");
        if (invoice.taxRate() == null || invoice.taxRate().compareTo(BigDecimal.ZERO) < 0
                || invoice.taxRate().compareTo(BigDecimal.ONE) > 0) {
            throw invalid("invalid_tax_rate", prefix + ".taxRate 必须在 0 到 1 之间");
        }
    }

    private static void requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) throw invalid("required_field", field + " 不能为空");
        if (value.length() > maxLength) throw invalid("field_too_long", field + " 长度不能超过 " + maxLength);
    }

    private static void requireAmount(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(MAX_AMOUNT) > 0) {
            throw invalid("invalid_amount", field + " 必须在 0 到 " + MAX_AMOUNT.toPlainString() + " 之间");
        }
    }

    private static TaxValidationException invalid(String code, String message) {
        return new TaxValidationException(code, message);
    }
}
