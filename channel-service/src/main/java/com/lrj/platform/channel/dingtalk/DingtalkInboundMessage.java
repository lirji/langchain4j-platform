package com.lrj.platform.channel.dingtalk;

/**
 * 从钉钉机器人消息回调解析出的规范化入站消息（当前只处理群内 @机器人 的 {@code text} 消息）。
 *
 * @param msgId           钉钉消息 id，用于去重（钉钉可能重投）
 * @param conversationId  群会话 id；回复群消息时作为 {@code openConversationId}
 * @param senderStaffId   发送人（客服）的 userId；作 /chat 的 chatId 隔离会话记忆
 * @param senderNick      发送人昵称（仅日志/展示用）
 * @param text            文本内容（已剔除 @机器人 的部分由钉钉侧处理，这里再 trim）
 */
public record DingtalkInboundMessage(String msgId, String conversationId,
                                     String senderStaffId, String senderNick, String text) {}
