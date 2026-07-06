package com.lrj.platform.conversation;

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

    @Test
    void chatUsesAugmentedPromptAndReturnsTenantMetadata() {
        Assistant assistant = mock(Assistant.class);
        RagPromptAugmenter augmenter = mock(RagPromptAugmenter.class);
        when(augmenter.augment("hello")).thenReturn("context\nhello");
        when(assistant.chat("context\nhello")).thenReturn("reply");
        ConversationController controller = new ConversationController(assistant, augmenter);
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));

        Map<String, Object> response = controller.chat("c1", Map.of("message", "hello"));

        assertThat(response).containsEntry("reply", "reply")
                .containsEntry("chatId", "c1")
                .containsEntry("tenantId", "acme")
                .containsEntry("userId", "alice");
        verify(assistant).chat("context\nhello");
    }
}
