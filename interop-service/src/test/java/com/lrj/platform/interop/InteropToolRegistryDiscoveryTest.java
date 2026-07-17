package com.lrj.platform.interop;

import com.lrj.platform.protocol.interop.McpToolDescriptor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InteropToolRegistryDiscoveryTest：验证 {@link InteropToolRegistry} 的 live 能力发现行为——
 * 无 client 或发现抛异常/返回空时回退静态工具集，发现成功时采用 live 工具，并按 TTL 缓存与过期后重取。
 */
class InteropToolRegistryDiscoveryTest {

    private static McpToolDescriptor tool(String name) {
        return new McpToolDescriptor(name, name, Map.of("type", "object"));
    }

    @Test
    void staticRegistryWhenNoClient() {
        InteropToolRegistry registry = new InteropToolRegistry();

        assertThat(registry.capabilityNames())
                .contains(InteropToolRegistry.PING_TOOL,
                        InteropToolRegistry.AGENT_RUN_TOOL,
                        InteropToolRegistry.AGENT_DAG_PLAN_RUN_ASYNC_TOOL);
    }

    @Test
    void usesLiveToolsWhenDiscoverySucceeds() {
        InteropToolRegistry registry = new InteropToolRegistry(
                () -> List.of(tool("platform.agent.run"), tool("platform.agent.brand_new")),
                Duration.ofSeconds(60));

        assertThat(registry.capabilityNames())
                .contains(InteropToolRegistry.PING_TOOL, "platform.agent.brand_new")
                .doesNotContain(InteropToolRegistry.AGENT_DAG_PLAN_RUN_TOOL);
    }

    @Test
    void fallsBackToStaticWhenDiscoveryThrows() {
        InteropToolRegistry registry = new InteropToolRegistry(() -> {
            throw new RuntimeException("agent unreachable");
        }, Duration.ofSeconds(60));

        // ping (local built-in) + 4 static agent tools
        assertThat(registry.capabilityNames())
                .contains(InteropToolRegistry.PING_TOOL,
                        InteropToolRegistry.AGENT_RUN_TOOL,
                        InteropToolRegistry.AGENT_DAG_PLAN_RUN_TOOL);
    }

    @Test
    void fallsBackToStaticWhenDiscoveryReturnsEmpty() {
        InteropToolRegistry registry = new InteropToolRegistry(List::of, Duration.ofSeconds(60));

        assertThat(registry.capabilityNames()).contains(InteropToolRegistry.AGENT_RUN_TOOL);
    }

    @Test
    void cachesWithinTtl() {
        AtomicInteger calls = new AtomicInteger();
        InteropToolRegistry registry = new InteropToolRegistry(() -> {
            calls.incrementAndGet();
            return List.of(tool("platform.agent.run"));
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
            return List.of(tool("platform.agent.run"));
        }, Duration.ofMillis(1));

        registry.tools();
        Thread.sleep(5);
        registry.tools();

        assertThat(calls.get()).isEqualTo(2);
    }
}
