package com.lrj.platform.protocol.tax;

import java.util.List;

/** 财税发票风险审查响应。风险结论来自确定性规则，叙述字段仅作辅助说明。 */
public record TaxInvoiceReviewReply(String reviewId,
                                    String tenantId,
                                    String ruleSetVersion,
                                    String overallRisk,
                                    int invoiceCount,
                                    int findingCount,
                                    List<TaxRiskFinding> findings,
                                    List<TaxPolicyEvidence> evidence,
                                    String narrative,
                                    String narrativeMode,
                                    String disclaimer) {

    public TaxInvoiceReviewReply {
        findings = findings == null ? List.of() : List.copyOf(findings);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
