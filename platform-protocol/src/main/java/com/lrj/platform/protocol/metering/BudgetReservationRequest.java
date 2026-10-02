package com.lrj.platform.protocol.metering;

/** 预留操作 ID 必须与调用级 service JWT 绑定；租户/用户只取验签身份。 */
public record BudgetReservationRequest(String operationId, long tokens) {}
