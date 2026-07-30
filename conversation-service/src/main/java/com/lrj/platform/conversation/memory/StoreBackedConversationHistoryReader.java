package com.lrj.platform.conversation.memory;

import com.lrj.platform.protocol.conversation.ConversationHistoryMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 只导出最近的纯文本历史，使用独立消息数/字符数上限，避免把整个 memory store 或二进制媒体交给
 * candidate。
 */
public class StoreBackedConversationHistoryReader implements ConversationHistoryReader {

    static final int CONTRACT_MAX_MESSAGES = 32;
    static final int CONTRACT_MAX_MESSAGE_CHARS = 4000;

    private final ChatMemoryStore store;
    private final int maxMessages;
    private final int maxChars;
    private final int maxMessageChars;

    public StoreBackedConversationHistoryReader(ChatMemoryStore store, int maxMessages,
                                                int maxChars, int maxMessageChars) {
        this.store = Objects.requireNonNull(store);
        this.maxMessages = Math.min(CONTRACT_MAX_MESSAGES, Math.max(0, maxMessages));
        this.maxChars = Math.max(0, maxChars);
        this.maxMessageChars = Math.min(
                CONTRACT_MAX_MESSAGE_CHARS, Math.max(0, maxMessageChars));
    }

    @Override
    public List<ConversationHistoryMessage> snapshot(String tenantId, String chatId) {
        if (maxMessages == 0 || maxChars == 0 || maxMessageChars == 0) {
            return List.of();
        }
        String memoryId = Objects.requireNonNull(tenantId) + "::" + Objects.requireNonNull(chatId);
        List<ChatMessage> messages = store.getMessages(memoryId);
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<ConversationHistoryMessage> newestFirst = new ArrayList<>();
        int remaining = maxChars;
        for (int index = messages.size() - 1;
             index >= 0 && newestFirst.size() < maxMessages && remaining > 0;
             index--) {
            ConversationHistoryMessage mapped = map(messages.get(index), remaining);
            if (mapped == null) {
                continue;
            }
            newestFirst.add(mapped);
            remaining -= mapped.content().length();
        }
        Collections.reverse(newestFirst);
        return List.copyOf(newestFirst);
    }

    private ConversationHistoryMessage map(ChatMessage message, int remaining) {
        String role;
        String content;
        if (message instanceof UserMessage user) {
            if (!user.hasSingleText()) {
                return null;
            }
            role = "user";
            content = user.singleText();
        } else if (message instanceof AiMessage assistant) {
            role = "assistant";
            content = assistant.text();
        } else if (message instanceof SystemMessage system) {
            role = "system";
            content = system.text();
        } else if (message instanceof ToolExecutionResultMessage tool) {
            if (!tool.hasSingleText()) {
                return null;
            }
            role = "tool";
            content = tool.text();
        } else {
            return null;
        }
        if (content == null || content.isBlank()) {
            return null;
        }
        String normalized = content.strip();
        int limit = Math.min(Math.min(maxMessageChars, remaining), normalized.length());
        if (limit == 0) {
            return null;
        }
        return new ConversationHistoryMessage(role, normalized.substring(0, limit));
    }
}
