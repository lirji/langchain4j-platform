package com.lrj.platform.interop;

import com.lrj.platform.protocol.interop.McpToolCallReply;
import com.lrj.platform.protocol.interop.McpToolCallRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Component
public class InteropToolDispatcher {

    private final AgentInteropClient agentClient;

    public InteropToolDispatcher(AgentInteropClient agentClient) {
        this.agentClient = agentClient;
    }

    public McpToolCallReply dispatch(McpToolCallRequest request) {
        if (request == null || request.tool() == null || request.tool().isBlank()) {
            return new McpToolCallReply(null, false, null, "tool is required");
        }
        return switch (request.tool()) {
            case InteropToolRegistry.PING_TOOL -> ping(request);
            case InteropToolRegistry.AGENT_RUN_TOOL -> agentRun(request);
            case InteropToolRegistry.AGENT_RUN_ASYNC_TOOL -> agentRunAsync(request);
            case InteropToolRegistry.AGENT_DAG_PLAN_RUN_TOOL -> agentDagPlanRun(request);
            case InteropToolRegistry.AGENT_DAG_PLAN_RUN_ASYNC_TOOL -> agentDagPlanRunAsync(request);
            default -> new McpToolCallReply(request.tool(), false, null, "unknown tool");
        };
    }

    private McpToolCallReply ping(McpToolCallRequest request) {
        return new McpToolCallReply(request.tool(), true,
                Map.of("pong", request.arguments().getOrDefault("message", "ok")), null);
    }

    private McpToolCallReply agentRun(McpToolCallRequest request) {
        String goal = goal(request);
        if (goal == null) {
            return new McpToolCallReply(request.tool(), false, null, "goal is required");
        }
        try {
            return new McpToolCallReply(request.tool(), true, agentClient.run(goal), null);
        } catch (RuntimeException ex) {
            return agentFailure(request.tool(), ex);
        }
    }

    private McpToolCallReply agentRunAsync(McpToolCallRequest request) {
        String goal = goal(request);
        if (goal == null) {
            return new McpToolCallReply(request.tool(), false, null, "goal is required");
        }
        try {
            return new McpToolCallReply(request.tool(), true, agentClient.runAsync(goal, webhookUrl(request)), null);
        } catch (RuntimeException ex) {
            return agentFailure(request.tool(), ex);
        }
    }

    private McpToolCallReply agentDagPlanRun(McpToolCallRequest request) {
        String goal = goal(request);
        if (goal == null) {
            return new McpToolCallReply(request.tool(), false, null, "goal is required");
        }
        try {
            return new McpToolCallReply(request.tool(), true, agentClient.planDagAndRun(goal), null);
        } catch (RuntimeException ex) {
            return agentFailure(request.tool(), ex);
        }
    }

    private McpToolCallReply agentDagPlanRunAsync(McpToolCallRequest request) {
        String goal = goal(request);
        if (goal == null) {
            return new McpToolCallReply(request.tool(), false, null, "goal is required");
        }
        try {
            return new McpToolCallReply(request.tool(), true, agentClient.planDagAndRunAsync(goal, webhookUrl(request)), null);
        } catch (RuntimeException ex) {
            return agentFailure(request.tool(), ex);
        }
    }

    private String goal(McpToolCallRequest request) {
        Object goalValue = request.arguments().get("goal");
        if (!(goalValue instanceof String goal) || goal.isBlank()) {
            return null;
        }
        return goal;
    }

    private String webhookUrl(McpToolCallRequest request) {
        Object value = request.arguments().get("webhookUrl");
        return value instanceof String webhookUrl && !webhookUrl.isBlank() ? webhookUrl : null;
    }

    private McpToolCallReply agentFailure(String tool, RuntimeException ex) {
        if (ex instanceof HttpStatusCodeException statusEx) {
            return new McpToolCallReply(tool, false, null,
                    "agent-service returned HTTP " + statusEx.getStatusCode().value());
        }
        if (ex instanceof RestClientException) {
            return new McpToolCallReply(tool, false, null, ex.getMessage());
        }
        throw ex;
    }
}
