package com.lrj.platform.conversation.memory;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Chat Memory 装配（对齐单体 {@code memory/}）：接口化 {@link ChatMemoryStore}（内存默认 / Redis 可选）
 * + 三种滑窗 {@code app.conversation.memory.window-mode}（messages 默认 / tokens / summary）。
 *
 * <p>langchain4j spring starter 的 {@code @AiService} 会自动发现唯一的 {@link ChatMemoryProvider} bean 并注入，
 * 使 {@code Assistant.chat(@MemoryId ...)} 具备按会话隔离的多轮记忆。默认 in-memory + messages，零外部依赖。
 */
@Configuration
public class ChatMemoryConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryConfig.class);

    /** summary 模式压缩旧消息的指令（用简洁中文摘要，保留关键事实/用户诉求）。 */
    static final String SUMMARIZE_INSTRUCTION =
            "请把下面的对话压缩成简洁的中文摘要，只保留关键事实、结论与用户诉求，不要展开，不要寒暄：";

    @Bean
    @ConditionalOnProperty(name = "app.conversation.memory.store", havingValue = "redis")
    ChatMemoryStore redisChatMemoryStore(
            StringRedisTemplate redisTemplate,
            @Value("${app.conversation.memory.redis-ttl:P7D}") String ttl) {
        log.info("Chat memory store: redis (ttl={})", ttl);
        return new RedisChatMemoryStore(redisTemplate, DurationStyle.detectAndParse(ttl));
    }

    @Bean
    @ConditionalOnProperty(name = "app.conversation.memory.store", havingValue = "in-memory", matchIfMissing = true)
    ChatMemoryStore inMemoryChatMemoryStore() {
        return new InMemoryChatMemoryStore();
    }

    @Bean
    ChatMemoryProvider conversationChatMemoryProvider(
            ChatMemoryStore store,
            @Value("${app.conversation.memory.window-mode:messages}") String windowMode,
            @Value("${app.conversation.memory.max-messages:20}") int maxMessages,
            @Value("${app.conversation.memory.max-tokens:2000}") int maxTokens,
            @Value("${app.conversation.memory.token-model:gpt-4o-mini}") String tokenModel,
            ObjectProvider<ChatModel> chatModelProvider) {
        String mode = windowMode == null ? "messages" : windowMode.trim().toLowerCase();
        log.info("Chat memory window-mode: {} (maxMessages={}, maxTokens={})", mode, maxMessages, maxTokens);
        return switch (mode) {
            case "tokens" -> tokensProvider(store, maxTokens, tokenModel);
            case "summary" -> summaryProvider(store, maxMessages, chatModelProvider);
            default -> messagesProvider(store, maxMessages);
        };
    }

    static ChatMemoryProvider messagesProvider(ChatMemoryStore store, int maxMessages) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(maxMessages)
                .chatMemoryStore(store)
                .build();
    }

    static ChatMemoryProvider tokensProvider(ChatMemoryStore store, int maxTokens, String tokenModel) {
        TokenCountEstimator estimator = new OpenAiTokenCountEstimator(tokenModel);
        return memoryId -> TokenWindowChatMemory.builder()
                .id(memoryId)
                .maxTokens(maxTokens, estimator)
                .chatMemoryStore(store)
                .build();
    }

    static ChatMemoryProvider summaryProvider(ChatMemoryStore store, int maxMessages,
                                              ObjectProvider<ChatModel> chatModelProvider) {
        ChatModel summarizerModel = chatModelProvider.getObject();
        return memoryId -> new SummarizingChatMemory(memoryId, store, maxMessages,
                overflow -> summarizerModel.chat(SUMMARIZE_INSTRUCTION + "\n\n" + overflow));
    }
}
