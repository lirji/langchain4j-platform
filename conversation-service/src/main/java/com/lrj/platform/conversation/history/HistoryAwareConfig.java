package com.lrj.platform.conversation.history;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * History-aware 检索压缩装配。沿用平台成对 {@code @ConditionalOnProperty} 约定：
 * 关（默认）→ {@link NoopHistoryAwareQueryCompressor}；开 → {@link LlmHistoryAwareQueryCompressor}
 * （复用网关 {@link ChatModel} 与已有 {@link ChatMemoryStore} bean）。
 *
 * <p>开启后每轮 {@code /chat} 多一次 LLM 压缩调用（换取追问检索命中率），故默认关。
 */
@Configuration
public class HistoryAwareConfig {

    private static final Logger log = LoggerFactory.getLogger(HistoryAwareConfig.class);

    @Bean
    @ConditionalOnProperty(name = "app.conversation.history-aware.enabled", havingValue = "false", matchIfMissing = true)
    HistoryAwareQueryCompressor noopHistoryAwareQueryCompressor() {
        return new NoopHistoryAwareQueryCompressor();
    }

    @Bean
    @ConditionalOnProperty(name = "app.conversation.history-aware.enabled", havingValue = "true")
    HistoryAwareQueryCompressor llmHistoryAwareQueryCompressor(
            ChatModel chatModel,
            ChatMemoryStore chatMemoryStore,
            @Value("${app.conversation.history-aware.max-history-messages:10}") int maxHistoryMessages) {
        log.info("History-aware retrieval: enabled (maxHistoryMessages={})", maxHistoryMessages);
        return new LlmHistoryAwareQueryCompressor(chatMemoryStore, chatModel::chat, maxHistoryMessages);
    }
}
