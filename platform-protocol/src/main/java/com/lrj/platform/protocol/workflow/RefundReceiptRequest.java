package com.lrj.platform.protocol.workflow;

/** 回执查询沿用原规范化请求参数，不接受请求体选择租户或用户。 */
public record RefundReceiptRequest(String chatId, String message, String dedupeId, String webhookUrl) {}
