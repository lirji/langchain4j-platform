package com.lrj.platform.interop;

import com.lrj.platform.protocol.interop.McpToolDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * interop 暴露的 MCP 工具目录。
 *
 * <p>ping 是 interop 本地内建工具（恒在）；agent 工具来自 live capability discovery：开启时懒加载 +
 * TTL 从 agent-service 拉取，下游不可达时确定性回退到 {@link #STATIC_AGENT_TOOLS 静态默认}
 * （即无 discovery client 时的历史行为），永不因下游故障抛错或阻塞。
 */
public class InteropToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(InteropToolRegistry.class);

    public static final String PING_TOOL = "platform.ping";
    public static final String AGENT_RUN_TOOL = "platform.agent.run";
    public static final String AGENT_RUN_ASYNC_TOOL = "platform.agent.run_async";
    public static final String AGENT_DAG_PLAN_RUN_TOOL = "platform.agent.dag.plan_run";
    public static final String AGENT_DAG_PLAN_RUN_ASYNC_TOOL = "platform.agent.dag.plan_run_async";

    /** 下游不可达/未开启 discovery 时的静态回退 agent 工具集。 */
    public static final List<McpToolDescriptor> STATIC_AGENT_TOOLS = List.of(
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

    private static final McpToolDescriptor PING = new McpToolDescriptor(
            PING_TOOL, "Returns a deterministic pong response.", Map.of(
            "type", "object",
            "properties", Map.of(
                    "message", Map.of("type", "string"))));

    private final AgentCapabilityClient discoveryClient;
    private final Duration ttl;
    private volatile Cached cache;

    /** 静态目录（无 discovery）—— 保持历史行为，供单测直接 new。 */
    public InteropToolRegistry() {
        this(null, Duration.ofSeconds(60));
    }

    public InteropToolRegistry(AgentCapabilityClient discoveryClient, Duration ttl) {
        this.discoveryClient = discoveryClient;
        this.ttl = (ttl == null || ttl.isNegative() || ttl.isZero()) ? Duration.ofSeconds(60) : ttl;
    }

    public List<McpToolDescriptor> tools() {
        List<McpToolDescriptor> all = new ArrayList<>();
        all.add(PING);
        all.addAll(agentTools());
        return List.copyOf(all);
    }

    public Optional<McpToolDescriptor> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return tools().stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst();
    }

    public List<String> capabilityNames() {
        return tools().stream()
                .map(McpToolDescriptor::name)
                .toList();
    }

    /** live-or-fallback 的 agent 工具。懒加载 + TTL；失败回退 last-known-good 或静态默认，不抛不阻塞。 */
    private List<McpToolDescriptor> agentTools() {
        if (discoveryClient == null) {
            return STATIC_AGENT_TOOLS;
        }
        Cached current = cache;
        if (current != null && !current.isStale(ttl)) {
            return current.tools();
        }
        try {
            List<McpToolDescriptor> fresh = discoveryClient.discoverTools();
            if (fresh != null && !fresh.isEmpty()) {
                Cached updated = new Cached(Instant.now(), List.copyOf(fresh));
                cache = updated;
                return updated.tools();
            }
            log.debug("agent capability discovery returned no tools; using fallback");
        } catch (RuntimeException ex) {
            log.debug("agent capability discovery failed ({}); using fallback", ex.toString());
        }
        return current != null ? current.tools() : STATIC_AGENT_TOOLS;
    }

    private record Cached(Instant fetchedAt, List<McpToolDescriptor> tools) {

        boolean isStale(Duration ttl) {
            return Instant.now().isAfter(fetchedAt.plus(ttl));
        }
    }
}
