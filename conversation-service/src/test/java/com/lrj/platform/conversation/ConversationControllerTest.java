package com.lrj.platform.conversation;

import com.lrj.platform.conversation.cache.HashSemanticCacheEmbedder;
import com.lrj.platform.conversation.cache.InMemorySemanticCacheStore;
import com.lrj.platform.conversation.cache.SemanticCache;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationControllerTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static SemanticCache disabledCache() {
        return new SemanticCache(new HashSemanticCacheEmbedder(),
                new InMemorySemanticCacheStore(1000), false, 0.95);
    }

    @Test
    void chatUsesAugmentedPromptAndReturnsTenantMetadata() {
        Assistant assistant = mock(Assistant.class);
        RagPromptAugmenter augmenter = mock(RagPromptAugmenter.class);
        when(augmenter.augment("hello")).thenReturn("context\nhello");
        when(assistant.chat("context\nhello")).thenReturn("reply");
        ConversationController controller = new ConversationController(assistant, augmenter, disabledCache());
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));

        Map<String, Object> response = controller.chat("c1", Map.of("message", "hello"));

        assertThat(response).containsEntry("reply", "reply")
                .containsEntry("chatId", "c1")
                .containsEntry("tenantId", "acme")
                .containsEntry("userId", "alice");
        verify(assistant).chat("context\nhello");
    }

    @Test
    void chatShortCircuitsRagAndLlmOnSemanticCacheHit() {
        Assistant assistant = mock(Assistant.class);
        RagPromptAugmenter augmenter = mock(RagPromptAugmenter.class);
        when(augmenter.augment("hello")).thenReturn("context\nhello");
        when(assistant.chat("context\nhello")).thenReturn("first-reply");
        SemanticCache cache = new SemanticCache(new HashSemanticCacheEmbedder(),
                new InMemorySemanticCacheStore(1000), true, 0.95);
        ConversationController controller = new ConversationController(assistant, augmenter, cache);
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));

        Map<String, Object> first = controller.chat("c1", Map.of("message", "hello"));
        Map<String, Object> second = controller.chat("c1", Map.of("message", "hello"));

        assertThat(first).containsEntry("reply", "first-reply");
        assertThat(second).containsEntry("reply", "first-reply");
        // 第二次命中缓存：RAG 增强与 LLM 各自只被调用了一次（第一次未命中时）。
        verify(augmenter, org.mockito.Mockito.times(1)).augment("hello");
        verify(assistant, org.mockito.Mockito.times(1)).chat("context\nhello");
    }
}
