package com.lrj.platform.tax;

import com.lrj.platform.protocol.tax.TaxPolicyEvidence;

import java.util.List;

/** 检索当前租户可见财税政策证据的可模拟边界。 */
public interface TaxKnowledgeClient {
    List<TaxPolicyEvidence> findEvidence(TaxReviewOutcome outcome);
}
