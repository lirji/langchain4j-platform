package com.lrj.platform.protocol.metering;

/** 只有完整模型 usage 才发结算；预留、日、实际用量均绑定签名。 */
public record BudgetSettlementRequest(String operationId, String day, long reservedTokens, long actualTokens) {}
