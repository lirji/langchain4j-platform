package com.lrj.platform.tax;

import com.lrj.platform.protocol.tax.TaxPolicyEvidence;

import java.util.List;

/** 生成非权威辅助说明的可模拟边界。 */
public interface TaxNarrator {
    TaxNarrative narrate(TaxReviewOutcome outcome, List<TaxPolicyEvidence> evidence);
}
