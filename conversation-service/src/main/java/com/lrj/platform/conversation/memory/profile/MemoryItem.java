package com.lrj.platform.conversation.memory.profile;

/**
 * 持久化的用户长期记忆项。{@code id} 为归一化文本 hashCode 的十六进制（稳定去重键），
 * {@code sourceChatId} 记录来源会话，便于溯源。
 */
public record MemoryItem(String id, String text, String type, long createdAtEpochMs, String sourceChatId) {
}
