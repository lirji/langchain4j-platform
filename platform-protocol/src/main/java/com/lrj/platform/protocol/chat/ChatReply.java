package com.lrj.platform.protocol.chat;

/**
 * Conversation 服务的最小响应契约。后续服务间调用优先依赖 protocol DTO，
 * 避免直接引用对方 controller 内部模型。
 */
public record ChatReply(String reply, String chatId, String tenantId, String userId) {
}
