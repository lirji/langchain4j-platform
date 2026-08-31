package com.lrj.platform.tax;

import com.lrj.platform.protocol.tax.TaxInvoiceReviewReply;
import com.lrj.platform.protocol.tax.TaxInvoiceReviewRequest;
import com.lrj.platform.security.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 受 {@code tax-review} scope 保护的发票风险审查入口。 */
@RestController
public class TaxInvoiceReviewController {

    private final TaxInvoiceReviewService service;

    public TaxInvoiceReviewController(TaxInvoiceReviewService service) {
        this.service = service;
    }

    @PostMapping("/tax/invoices/review")
    public TaxInvoiceReviewReply review(@RequestBody(required = false) TaxInvoiceReviewRequest request) {
        if (!TenantContext.current().hasScope("tax-review")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "tax-review scope required");
        }
        return service.review(request);
    }
}
