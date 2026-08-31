package com.lrj.platform.protocol.tax;

/** 一条由确定性规则产生的风险发现；AI 不得修改这些字段。 */
public record TaxRiskFinding(String code,
                             String severity,
                             String invoiceRef,
                             String message) {
}
