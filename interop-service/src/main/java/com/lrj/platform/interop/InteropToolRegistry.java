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
 * <p>ping 是 interop 本地内建工具（恒在）；agent 工具只来自 AgentScope live capability discovery。
 * TTL 内使用缓存，刷新失败使用 last-known-good；冷启动且 AgentScope 不可达时只暴露 ping，不再在
 * Java 侧复制 AgentScope 的工具描述符。
 */
public class InteropToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(InteropToolRegistry.class);

    public static final String PING_TOOL = "platform.ping";
    public static final String AGENT_RUN_TOOL = "platform.agent.run";
    public static final String AGENT_RUN_ASYNC_TOOL = "platform.agent.run_async";
    public static final String AGENT_DAG_PLAN_RUN_TOOL = "platform.agent.dag.plan_run";
    public static final String AGENT_DAG_PLAN_RUN_ASYNC_TOOL = "platform.agent.dag.plan_run_async";

    private static final McpToolDescriptor PING = new McpToolDescriptor(
            PING_TOOL, "Returns a deterministic pong response.", Map.of(
            "type", "object",
            "properties", Map.of(
                    "message", Map.of("type", "string"))));

    private final AgentCapabilityClient discoveryClient;
    private final Duration ttl;
    private volatile Cached cache;

    /** 无 discovery client 时只暴露 interop 本地工具。 */
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

    /** live discovery 的 agent 工具。懒加载 + TTL；失败只回退 last-known-good，不复制静态目录。 */
    private List<McpToolDescriptor> agentTools() {
        if (discoveryClient == null) {
            return List.of();
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
        return current != null ? current.tools() : List.of();
    }

    private record Cached(Instant fetchedAt, List<McpToolDescriptor> tools) {

        boolean isStale(Duration ttl) {
            return Instant.now().isAfter(fetchedAt.plus(ttl));
        }
    }
}
