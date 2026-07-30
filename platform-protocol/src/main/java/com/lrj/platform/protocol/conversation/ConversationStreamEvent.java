package com.lrj.platform.protocol.conversation;

/** 独立 candidate SSE 的语言中立事件 envelope。 */
public record ConversationStreamEvent(long sequence, String type, String data) {}
