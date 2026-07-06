package com.lrj.platform.interop;

import com.lrj.platform.protocol.interop.AgentCard;
import com.lrj.platform.protocol.interop.McpToolCallReply;
import com.lrj.platform.protocol.interop.McpToolCallRequest;
import com.lrj.platform.protocol.interop.McpToolDescriptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

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
        return new AgentCard(
                "langchain4j-platform",
                "Platform agent interoperability surface backed by service-net tools.",
                "0.1.0",
                List.of(
                        "a2a.agent-card",
                        "mcp.tools.list",
                        "mcp.tools.call",
                        "platform.agent.run",
                        "platform.agent.run_async",
                        "platform.agent.dag.plan_run",
                        "platform.agent.dag.plan_run_async"),
                Map.of(
                        "agentCard", "/interop/agent-card",
                        "mcpTools", "/interop/mcp/tools",
                        "mcpCall", "/interop/mcp/call"));
    }

    @GetMapping("/interop/mcp/tools")
    public List<McpToolDescriptor> tools() {
        return registry.tools();
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
}
