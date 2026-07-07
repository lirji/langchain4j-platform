package com.lrj.platform.protocol.conversation;

/**
 * workflow → conversation 的「结构化工单抽取」请求契约（C2 / B9 决策：抽取归 conversation 新端点）。
 *
 * @param message 用户原始退款请求文本
 */
public record TicketDraftRequest(String message) {
}
