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
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * interop 暴露的 MCP 工具目录。
 *
 * <p>ping 是 interop 本地内建工具（恒在）；agent 工具只来自 AgentScope live capability discovery。
 * TTL 内使用缓存，刷新失败使用 last-known-good；冷启动且 AgentScope 不可达时只暴露 ping，不再在
 * Java 侧复制 AgentScope 的工具描述符。
 */
public class InteropToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(InteropToolRegistry.class);
    private static final Pattern REVISION = Pattern.compile("[0-9a-f]{64}");

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
    private final CapabilityRegistryStore store;
    private volatile Cached cache;

    /** 无 discovery client 时只暴露 interop 本地工具。 */
    public InteropToolRegistry() {
        this(null, Duration.ofSeconds(60), new InMemoryCapabilityRegistryStore());
    }

    public InteropToolRegistry(AgentCapabilityClient discoveryClient, Duration ttl) {
        this(discoveryClient, ttl, new InMemoryCapabilityRegistryStore());
    }

    public InteropToolRegistry(AgentCapabilityClient discoveryClient,
                               Duration ttl,
                               CapabilityRegistryStore store) {
        this.discoveryClient = discoveryClient;
        this.ttl = (ttl == null || ttl.isNegative() || ttl.isZero()) ? Duration.ofSeconds(60) : ttl;
        this.store = store == null ? new InMemoryCapabilityRegistryStore() : store;
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
        if (current == null) {
            try {
                current = store.load()
                        .filter(InteropToolRegistry::validRegistry)
                        .map(registry -> new Cached(Instant.now(), registry.capabilities()))
                        .orElse(null);
            } catch (RuntimeException exception) {
                log.warn("persisted capability registry is unavailable; using live discovery");
            }
            cache = current;
        }
        if (current != null && !current.isStale(ttl)) {
            return current.tools();
        }
        try {
            var registry = discoveryClient.discoverRegistry();
            List<McpToolDescriptor> fresh = validRegistry(registry)
                    ? registry.capabilities() : List.of();
            if (fresh != null && !fresh.isEmpty()) {
                Cached updated = new Cached(Instant.now(), List.copyOf(fresh));
                cache = updated;
                try {
                    store.save(registry);
                } catch (RuntimeException exception) {
                    log.warn("capability LKG persistence failed; serving fresh in-memory registry");
                }
                return updated.tools();
            }
            log.debug("agent capability discovery returned no tools; using fallback");
        } catch (RuntimeException ex) {
            log.debug("agent capability discovery failed ({}); using fallback", ex.toString());
        }
        return current != null ? current.tools() : List.of();
    }

    private static boolean validRegistry(
            com.lrj.platform.protocol.interop.AgentCapabilityRegistry registry) {
        if (registry == null
                || !"agent-capability-registry.v1".equals(registry.schemaVersion())
                || registry.revision() == null
                || !REVISION.matcher(registry.revision()).matches()
                || registry.capabilities() == null
                || registry.capabilities().isEmpty()) {
            return false;
        }
        Set<String> names = new HashSet<>();
        for (McpToolDescriptor descriptor : registry.capabilities()) {
            if (descriptor == null || descriptor.name() == null || descriptor.name().isBlank()
                    || !names.add(descriptor.name())) {
                return false;
            }
        }
        return true;
    }

    private record Cached(Instant fetchedAt, List<McpToolDescriptor> tools) {

        boolean isStale(Duration ttl) {
            return Instant.now().isAfter(fetchedAt.plus(ttl));
        }
    }
}
