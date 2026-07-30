package com.lrj.platform.protocol.conversation;

/** Candidate 可见的语言中立、只读历史消息。 */
public record ConversationHistoryMessage(String role, String content) {}
