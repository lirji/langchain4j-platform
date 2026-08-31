package com.lrj.platform.protocol.tax;

/** 来自当前租户可见知识库的政策证据摘要。 */
public record TaxPolicyEvidence(String citationId,
                                String docId,
                                String displayName,
                                String source,
                                Double score,
                                String excerpt) {
}
