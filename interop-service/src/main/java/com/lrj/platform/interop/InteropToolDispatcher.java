package com.lrj.platform.interop;

import com.lrj.platform.protocol.interop.McpToolCallReply;
import com.lrj.platform.protocol.interop.McpToolCallRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.Set;

/**
 * MCP 工具调用调度器：按 {@link McpToolCallRequest#tool()} 将请求分派到本地 {@code platform.ping}
 * 或经 {@link AgentInteropClient} 代理到 AgentScope（run / run_async / dag.plan_run[_async]），
 * 并把下游 HTTP/网络异常规约为带错误信息的 {@link McpToolCallReply}。供 {@link InteropController} 使用。
 *
 * <p>代理 agent 工具前先查 {@link InteropToolRegistry} 是否真的宣告过该工具。Java 侧的 case 分支只是
 * 「能怎么代理」的映射，不构成能力事实源；不做这层校验的话，AgentScope live discovery 没有宣告的工具
 * 也会被照常代理出去，Java Agent 退役门禁「互操作层只依赖 AgentScope live discovery」就无法成立。
 */
@Component
public class InteropToolDispatcher {

    /** interop 能代理到 AgentScope 的工具；是否可调用仍以 live discovery 宣告为准。 */
    private static final Set<String> PROXYABLE_AGENT_TOOLS = Set.of(
            InteropToolRegistry.AGENT_RUN_TOOL,
            InteropToolRegistry.AGENT_RUN_ASYNC_TOOL,
            InteropToolRegistry.AGENT_DAG_PLAN_RUN_TOOL,
            InteropToolRegistry.AGENT_DAG_PLAN_RUN_ASYNC_TOOL);

    private final AgentInteropClient agentClient;
    private final InteropToolRegistry registry;

    public InteropToolDispatcher(AgentInteropClient agentClient, InteropToolRegistry registry) {
        this.agentClient = agentClient;
        this.registry = registry;
    }

    public McpToolCallReply dispatch(McpToolCallRequest request) {
        if (request == null || request.tool() == null || request.tool().isBlank()) {
            return new McpToolCallReply(null, false, null, "tool is required");
        }
        String tool = request.tool();
        if (InteropToolRegistry.PING_TOOL.equals(tool)) {
            return ping(request);
        }
        if (!PROXYABLE_AGENT_TOOLS.contains(tool)) {
            return new McpToolCallReply(tool, false, null, "unknown tool");
        }
        if (!registry.capabilityNames().contains(tool)) {
            return new McpToolCallReply(tool, false, null,
                    "tool is not advertised by AgentScope capability discovery");
        }
        return switch (tool) {
            case InteropToolRegistry.AGENT_RUN_TOOL -> agentRun(request);
            case InteropToolRegistry.AGENT_RUN_ASYNC_TOOL -> agentRunAsync(request);
            case InteropToolRegistry.AGENT_DAG_PLAN_RUN_TOOL -> agentDagPlanRun(request);
            case InteropToolRegistry.AGENT_DAG_PLAN_RUN_ASYNC_TOOL -> agentDagPlanRunAsync(request);
            default -> new McpToolCallReply(tool, false, null, "unknown tool");
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
                    "AgentScope returned HTTP " + statusEx.getStatusCode().value());
        }
        if (ex instanceof RestClientException) {
            return new McpToolCallReply(tool, false, null, ex.getMessage());
        }
        throw ex;
    }
}
