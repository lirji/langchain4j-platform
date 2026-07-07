package com.lrj.platform.channel.feishu;

/**
 * 从飞书事件解析出的规范化入站消息（当前只处理 {@code im.message.receive_v1} 的 text 消息）。
 *
 * @param messageId 飞书消息 id（{@code om_...}），用于去重（飞书会重投）
 * @param openId    发送者 open_id（{@code ou_...}），回复的 receive_id
 * @param chatId    会话 id（{@code oc_...}）
 * @param text      文本内容
 */
public record FeishuInboundMessage(String messageId, String openId, String chatId, String text) {}
