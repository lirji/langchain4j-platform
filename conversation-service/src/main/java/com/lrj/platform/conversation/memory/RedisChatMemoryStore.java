package com.lrj.platform.conversation.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis 版 {@link ChatMemoryStore}（对齐单体 {@code RedisChatMemoryStore}）：按 {@code chat:mem:<memoryId>}
 * 存 langchain4j 序列化的消息 JSON + TTL。多副本共享同一份会话记忆、重启不丢。
 *
 * <p>{@code memoryId} 已由 {@code ConversationController} 用 {@code <tenantId>::<chatId>} 组合，
 * 故租户隔离在 key 层天然成立。默认不启用（{@code app.conversation.memory.store=in-memory}），
 * 仅当切到 {@code redis} 时由 {@link ChatMemoryConfig} 装配，保证零依赖 dev/test。
 */
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final String KEY_PREFIX = "chat:mem:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisChatMemoryStore(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = redis.opsForValue().get(key(memoryId));
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(ChatMessageDeserializer.messagesFromJson(json));
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String json = ChatMessageSerializer.messagesToJson(messages);
        if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
            redis.opsForValue().set(key(memoryId), json, ttl);
        } else {
            redis.opsForValue().set(key(memoryId), json);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redis.delete(key(memoryId));
    }

    private static String key(Object memoryId) {
        return KEY_PREFIX + memoryId;
    }
}
