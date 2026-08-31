package com.lrj.platform.tax;

import com.lrj.platform.audit.AuditEventType;
import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.protocol.tax.TaxInvoiceReviewReply;
import com.lrj.platform.protocol.tax.TaxInvoiceReviewRequest;
import com.lrj.platform.protocol.tax.TaxPolicyEvidence;
import com.lrj.platform.security.TenantContext;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 发票审查编排：校验 → 确定性规则 → 租户证据 → AI/降级说明 → 脱敏审计。 */
@Service
public class TaxInvoiceReviewService {

    static final String DISCLAIMER = "本结果仅用于发票内部一致性辅助审查，不构成正式税务意见、真伪查验、抵扣结论或申报依据。";
    private final TaxRequestValidator validator;
    private final TaxInvoiceRuleEngine rules;
    private final TaxKnowledgeClient knowledge;
    private final TaxNarrator narrator;
    private final AuditLogger audit;
    private final TaxReviewProperties properties;

    public TaxInvoiceReviewService(TaxRequestValidator validator,
                                   TaxInvoiceRuleEngine rules,
                                   TaxKnowledgeClient knowledge,
                                   TaxNarrator narrator,
                                   AuditLogger audit,
                                   TaxReviewProperties properties) {
        this.validator = validator;
        this.rules = rules;
        this.knowledge = knowledge;
        this.narrator = narrator;
        this.audit = audit;
        this.properties = properties;
    }

    public TaxInvoiceReviewReply review(TaxInvoiceReviewRequest request) {
        ValidatedTaxInvoiceBatch batch = validator.validate(request);
        TaxReviewOutcome outcome = rules.review(batch);
        List<TaxPolicyEvidence> evidence = knowledge.findEvidence(outcome);
        TaxNarrative narrative = narrator.narrate(outcome, evidence);
        String reviewId = UUID.randomUUID().toString();
        TaxInvoiceReviewReply reply = new TaxInvoiceReviewReply(
                reviewId,
                TenantContext.current().tenantId(),
                properties.getRuleSetVersion(),
                outcome.overallRisk(),
                batch.invoices().size(),
                outcome.findings().size(),
                outcome.findings(),
                evidence,
                narrative.text(),
                narrative.mode(),
                DISCLAIMER);
        audit.record(AuditEventType.TAX_INVOICE_REVIEWED, auditFields(reply));
        return reply;
    }

    static Map<String, Object> auditFields(TaxInvoiceReviewReply reply) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reviewId", reply.reviewId());
        fields.put("ruleSetVersion", reply.ruleSetVersion());
        fields.put("invoiceCount", reply.invoiceCount());
        fields.put("findingCount", reply.findingCount());
        fields.put("findingCodes", reply.findings().stream().map(item -> item.code()).distinct().sorted().toList());
        fields.put("overallRisk", reply.overallRisk());
        fields.put("evidenceCount", reply.evidence().size());
        fields.put("narrativeMode", reply.narrativeMode());
        return fields;
    }
}
