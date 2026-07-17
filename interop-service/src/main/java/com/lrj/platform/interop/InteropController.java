package com.lrj.platform.interop;

import com.lrj.platform.protocol.interop.AgentCard;
import com.lrj.platform.protocol.interop.McpToolCallReply;
import com.lrj.platform.protocol.interop.McpToolCallRequest;
import com.lrj.platform.protocol.interop.McpToolDescriptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * interop-service 的 MCP 互操作入口：对外暴露 Agent Card（{@code /interop/agent-card}、
 * {@code /interop/a2a/agent-card}）与 MCP 工具面（{@code /interop/mcp/tools}、
 * {@code /interop/mcp/tools/{toolName}}、{@code /interop/mcp/call}）。工具目录来自
 * {@link InteropToolRegistry}，工具调用经 {@link InteropToolDispatcher} 转发到 agent-service。
 */
@RestController
public class InteropController {

    private final InteropToolRegistry registry;
    private final InteropToolDispatcher dispatcher;

    public InteropController(InteropToolRegistry registry, InteropToolDispatcher dispatcher) {
        this.registry = registry;
        this.dispatcher = dispatcher;
    }

    @GetMapping("/interop/agent-card")
    public AgentCard agentCard() {
        return buildAgentCard();
    }

    @GetMapping("/interop/a2a/agent-card")
    public AgentCard a2aAgentCard() {
        return buildAgentCard();
    }

    private AgentCard buildAgentCard() {
        return new AgentCard(
                "langchain4j-platform",
                "Platform agent interoperability surface backed by service-net tools.",
                "0.1.0",
                concat(
                        "a2a.agent-card",
                        "mcp.tools.list",
                        "mcp.tools.call"),
                Map.of(
                        "agentCard", "/interop/agent-card",
                        "a2aAgentCard", "/interop/a2a/agent-card",
                        "mcpTools", "/interop/mcp/tools",
                        "mcpTool", "/interop/mcp/tools/{toolName}",
                        "mcpCall", "/interop/mcp/call"));
    }

    @GetMapping("/interop/mcp/tools")
    public List<McpToolDescriptor> tools() {
        return registry.tools();
    }

    @GetMapping("/interop/mcp/tools/{toolName}")
    public ResponseEntity<?> tool(@PathVariable String toolName) {
        return registry.find(toolName)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "unknown tool")));
    }

    @PostMapping("/interop/mcp/call")
    public ResponseEntity<?> call(@RequestBody McpToolCallRequest request) {
        McpToolCallReply reply = dispatcher.dispatch(request);
        if (reply.success()) {
            return ResponseEntity.ok(reply);
        }
        if (request == null || request.tool() == null || request.tool().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", reply.error()));
        }
        HttpStatus status = "unknown tool".equals(reply.error()) ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY;
        if ("goal is required".equals(reply.error())) {
            status = HttpStatus.BAD_REQUEST;
        }
        return ResponseEntity.status(status).body(reply);
    }

    private List<String> concat(String... builtIns) {
        java.util.ArrayList<String> capabilities = new java.util.ArrayList<>(List.of(builtIns));
        capabilities.addAll(registry.capabilityNames());
        return List.copyOf(capabilities);
    }
}
