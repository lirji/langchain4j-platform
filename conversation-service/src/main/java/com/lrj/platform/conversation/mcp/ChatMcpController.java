package com.lrj.platform.conversation.mcp;

import com.lrj.platform.security.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code POST /chat/mcp}：工具来自 MCP server 的对话（默认关）。
 * 未启用（{@code app.conversation.mcp.enabled=false}）→ 返回明确禁用提示。
 */
@RestController
public class ChatMcpController {

    private final ObjectProvider<McpAssistant> mcpAssistantProvider;

    public ChatMcpController(ObjectProvider<McpAssistant> mcpAssistantProvider) {
        this.mcpAssistantProvider = mcpAssistantProvider;
    }

    @PostMapping("/chat/mcp")
    public Map<String, Object> chatMcp(@RequestBody Map<String, String> body) {
        TenantContext.Tenant tenant = TenantContext.current();
        McpAssistant assistant = mcpAssistantProvider.getIfAvailable();
        if (assistant == null) {
            return Map.of("error", "MCP not enabled. Set app.conversation.mcp.enabled=true.",
                    "tenantId", tenant.tenantId());
        }
        String reply = assistant.chat(body.getOrDefault("message", ""));
        return Map.of("reply", reply, "tenantId", tenant.tenantId(), "userId", tenant.userId());
    }
}
