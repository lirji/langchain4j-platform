package com.lrj.platform.interop;

import com.lrj.platform.protocol.interop.McpToolDescriptor;
import com.lrj.platform.protocol.interop.AgentCapabilityRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InteropToolRegistryDiscoveryTest：验证 {@link InteropToolRegistry} 的 live 能力发现行为——
 * 无 client 或冷启动发现失败时只暴露本地工具，发现成功时采用 live 工具，并按 TTL 缓存与过期后重取。
 */
class InteropToolRegistryDiscoveryTest {

    private static McpToolDescriptor tool(String name) {
        return new McpToolDescriptor(name, name, Map.of("type", "object"));
    }

    private static AgentCapabilityRegistry registry(String revision, McpToolDescriptor... tools) {
        return new AgentCapabilityRegistry(
                "agent-capability-registry.v1", revision, List.of(tools));
    }

    private static String revision(char value) {
        return String.valueOf(value).repeat(64);
    }

    @Test
    void exposesOnlyLocalToolsWhenNoClient() {
        InteropToolRegistry registry = new InteropToolRegistry();

        assertThat(registry.capabilityNames())
                .containsExactly(InteropToolRegistry.PING_TOOL);
    }

    @Test
    void usesLiveToolsWhenDiscoverySucceeds() {
        InteropToolRegistry registry = new InteropToolRegistry(
                () -> registry(revision('a'), tool("platform.agent.run"), tool("platform.agent.brand_new")),
                Duration.ofSeconds(60));

        assertThat(registry.capabilityNames())
                .contains(InteropToolRegistry.PING_TOOL, "platform.agent.brand_new")
                .doesNotContain(InteropToolRegistry.AGENT_DAG_PLAN_RUN_TOOL);
    }

    @Test
    void doesNotAdvertiseAgentToolsWhenColdStartDiscoveryThrows() {
        InteropToolRegistry registry = new InteropToolRegistry(() -> {
            throw new RuntimeException("agent unreachable");
        }, Duration.ofSeconds(60));

        assertThat(registry.capabilityNames()).containsExactly(InteropToolRegistry.PING_TOOL);
    }

    @Test
    void doesNotAdvertiseAgentToolsWhenColdStartDiscoveryReturnsEmpty() {
        InteropToolRegistry registry = new InteropToolRegistry(
                () -> registry(revision('a')), Duration.ofSeconds(60));

        assertThat(registry.capabilityNames()).containsExactly(InteropToolRegistry.PING_TOOL);
    }

    @Test
    void cachesWithinTtl() {
        AtomicInteger calls = new AtomicInteger();
        InteropToolRegistry registry = new InteropToolRegistry(() -> {
            calls.incrementAndGet();
            return registry(revision('a'), tool("platform.agent.run"));
        }, Duration.ofMinutes(10));

        registry.tools();
        registry.tools();
        registry.tools();

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void refetchesAfterTtlExpiry() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        InteropToolRegistry registry = new InteropToolRegistry(() -> {
            calls.incrementAndGet();
            return registry(revision((char) ('a' + calls.get() - 1)), tool("platform.agent.run"));
        }, Duration.ofMillis(1));

        registry.tools();
        Thread.sleep(5);
        registry.tools();

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void usesLastKnownGoodWhenRefreshFails() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        InteropToolRegistry registry = new InteropToolRegistry(() -> {
            if (calls.incrementAndGet() == 1) {
                return registry(revision('a'), tool("platform.agent.run"));
            }
            throw new RuntimeException("agent unreachable");
        }, Duration.ofMillis(1));

        assertThat(registry.capabilityNames()).contains("platform.agent.run");
        Thread.sleep(5);

        assertThat(registry.capabilityNames()).contains("platform.agent.run");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void restoresPersistedLastKnownGoodAfterProcessRestart() {
        InMemoryCapabilityRegistryStore store = new InMemoryCapabilityRegistryStore();
        InteropToolRegistry first = new InteropToolRegistry(
                () -> registry(revision('a'), tool("platform.agent.run")),
                Duration.ofSeconds(60), store);
        assertThat(first.capabilityNames()).contains("platform.agent.run");

        InteropToolRegistry restarted = new InteropToolRegistry(() -> {
            throw new RuntimeException("agent unavailable after restart");
        }, Duration.ofSeconds(60), store);

        assertThat(restarted.capabilityNames()).contains("platform.agent.run");
    }
}
