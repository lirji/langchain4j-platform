package com.lrj.platform.conversation.mcp;

import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatMcpControllerTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    @Test
    void chatMcp_enabled_dispatchesToAssistant() {
        McpAssistant assistant = mock(McpAssistant.class);
        when(assistant.chat("现在几点")).thenReturn("现在是 10 点");
        ObjectProvider<McpAssistant> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(assistant);
        ChatMcpController controller = new ChatMcpController(provider);
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));

        Map<String, Object> res = controller.chatMcp(Map.of("message", "现在几点"));

        assertThat(res).containsEntry("reply", "现在是 10 点").containsEntry("tenantId", "acme");
    }

    @SuppressWarnings("unchecked")
    @Test
    void chatMcp_disabled_returnsError() {
        ObjectProvider<McpAssistant> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatMcpController controller = new ChatMcpController(provider);
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));

        Map<String, Object> res = controller.chatMcp(Map.of("message", "hi"));

        assertThat(res).containsKey("error");
        assertThat((String) res.get("error")).contains("MCP not enabled");
    }
}
