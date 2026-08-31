package com.lrj.platform.tax;

import com.lrj.platform.protocol.tax.TaxRiskFinding;

import java.util.List;

/** 确定性规则结果。 */
record TaxReviewOutcome(String overallRisk, List<TaxRiskFinding> findings) {

    TaxReviewOutcome {
        findings = List.copyOf(findings);
    }
}
