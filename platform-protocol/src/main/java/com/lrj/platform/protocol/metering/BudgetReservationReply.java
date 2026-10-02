package com.lrj.platform.protocol.metering;

/** 调用起始日与原额度用于同一预留的幂等结算，不携带模型内容。 */
public record BudgetReservationReply(String operationId, String day, long reservedTokens) {}
