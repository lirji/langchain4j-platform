package com.lrj.platform.interop;

import com.lrj.platform.protocol.interop.McpToolDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class InteropToolRegistry {

    public static final String PING_TOOL = "platform.ping";
    public static final String AGENT_RUN_TOOL = "platform.agent.run";
    public static final String AGENT_RUN_ASYNC_TOOL = "platform.agent.run_async";
    public static final String AGENT_DAG_PLAN_RUN_TOOL = "platform.agent.dag.plan_run";
    public static final String AGENT_DAG_PLAN_RUN_ASYNC_TOOL = "platform.agent.dag.plan_run_async";

    public List<McpToolDescriptor> tools() {
        return List.of(
                new McpToolDescriptor(PING_TOOL, "Returns a deterministic pong response.", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "message", Map.of("type", "string")))),
                new McpToolDescriptor(AGENT_RUN_TOOL, "Runs the platform agent through agent-service.", Map.of(
                        "type", "object",
                        "required", List.of("goal"),
                        "properties", Map.of(
                                "goal", Map.of("type", "string"),
                                "webhookUrl", Map.of("type", "string")))),
                new McpToolDescriptor(AGENT_RUN_ASYNC_TOOL, "Starts an async platform agent run through agent-service.", Map.of(
                        "type", "object",
                        "required", List.of("goal"),
                        "properties", Map.of(
                                "goal", Map.of("type", "string"),
                                "webhookUrl", Map.of("type", "string")))),
                new McpToolDescriptor(AGENT_DAG_PLAN_RUN_TOOL, "Plans and runs a DAG agent workflow through agent-service.", Map.of(
                        "type", "object",
                        "required", List.of("goal"),
                        "properties", Map.of(
                                "goal", Map.of("type", "string")))),
                new McpToolDescriptor(AGENT_DAG_PLAN_RUN_ASYNC_TOOL, "Starts an async planned DAG agent workflow through agent-service.", Map.of(
                        "type", "object",
                        "required", List.of("goal"),
                        "properties", Map.of(
                                "goal", Map.of("type", "string"),
                                "webhookUrl", Map.of("type", "string")))));
    }
}
