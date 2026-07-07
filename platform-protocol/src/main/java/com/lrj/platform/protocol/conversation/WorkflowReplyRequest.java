package com.lrj.platform.protocol.conversation;

/**
 * workflow → conversation 的「答复生成」请求契约（C2）。
 *
 * <p>workflow-service 的退款 ServiceTask 在需要给用户生成一段受理/通过答复时，
 * 经 HTTP 调用 conversation-service 的答复生成端点，取代原先直连本地 ChatModel 的兜底。
 *
 * @param chatId  作用域化会话标识（通常为 {@code tenantId:chatId}），供 conversation 侧留痕/上下文用
 * @param message 用户原始退款请求文本
 */
public record WorkflowReplyRequest(String chatId, String message) {
}
