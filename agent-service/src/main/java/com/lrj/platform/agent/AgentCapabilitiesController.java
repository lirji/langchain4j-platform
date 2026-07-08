package com.lrj.platform.agent;

import com.lrj.platform.protocol.interop.McpToolDescriptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * live capability discovery 端点：向 interop-service 声明 agent-service 当前暴露的能力，
 * 以 {@link McpToolDescriptor} 表达（interop 直接作为其 MCP 工具目录透传）。
 *
 * <p>只在 agent 装配（{@code DeepAgentService} 存在）时挂端点；下游 interop 拉取失败时回退到
 * 其内置静态默认，与本端点声明的工具集一致，保证 discovery 与 fallback 行为对齐。
 */
@RestController
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true", matchIfMissing = true)
public class AgentCapabilitiesController {

    @GetMapping("/agent/capabilities")
    public List<McpToolDescriptor> capabilities() {
        return List.of(
                new McpToolDescriptor("platform.agent.run",
                        "Runs the platform agent through agent-service.", Map.of(
                        "type", "object",
                        "required", List.of("goal"),
                        "properties", Map.of(
                                "goal", Map.of("type", "string"),
                                "webhookUrl", Map.of("type", "string")))),
                new McpToolDescriptor("platform.agent.run_async",
                        "Starts an async platform agent run through agent-service.", Map.of(
                        "type", "object",
                        "required", List.of("goal"),
                        "properties", Map.of(
                                "goal", Map.of("type", "string"),
                                "webhookUrl", Map.of("type", "string")))),
                new McpToolDescriptor("platform.agent.dag.plan_run",
                        "Plans and runs a DAG agent workflow through agent-service.", Map.of(
                        "type", "object",
                        "required", List.of("goal"),
                        "properties", Map.of(
                                "goal", Map.of("type", "string")))),
                new McpToolDescriptor("platform.agent.dag.plan_run_async",
                        "Starts an async planned DAG agent workflow through agent-service.", Map.of(
                        "type", "object",
                        "required", List.of("goal"),
                        "properties", Map.of(
                                "goal", Map.of("type", "string"),
                                "webhookUrl", Map.of("type", "string")))));
    }
}
