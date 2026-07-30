package com.lrj.platform.conversation.memory;

import com.lrj.platform.protocol.conversation.ConversationHistoryMessage;

import java.util.List;

/** 从 Java 权威 memory store 导出有界只读快照；candidate 不得直接访问 store。 */
@FunctionalInterface
public interface ConversationHistoryReader {

    List<ConversationHistoryMessage> snapshot(String tenantId, String chatId);
}
