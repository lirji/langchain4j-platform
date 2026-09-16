package com.lrj.platform.interop;

import com.lrj.platform.protocol.agent.AgentRunReply;
import com.lrj.platform.protocol.interop.AgentCapabilityRegistry;
import com.lrj.platform.protocol.interop.McpToolCallReply;
import com.lrj.platform.protocol.interop.McpToolCallRequest;
import com.lrj.platform.protocol.interop.McpToolDescriptor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InteropToolDispatcherDiscoveryTest：Java Agent 退役门禁「互操作层只依赖 AgentScope live discovery」
 * 的机器检查。断言 {@link InteropToolDispatcher} 只代理 live discovery 真的宣告过的 agent 工具——
 * 宣告缺失（AgentScope 不可达、或该模式已下线）时必须拒绝且完全不碰下游，而不是靠 Java 侧的静态
 * case 分支照常代理。{@code platform.ping} 是 interop 本地工具，不受 discovery 影响。
 */
class InteropToolDispatcherDiscoveryTest {

    private static McpToolDescriptor tool(String name) {
        return new McpToolDescriptor(name, name, Map.of("type", "object"));
    }

    private static InteropToolRegistry registryAdvertising(String... tools) {
        return new InteropToolRegistry(
                () -> new AgentCapabilityRegistry("agent-capability-registry.v1", "a".repeat(64),
                        List.of(tools).stream().map(InteropToolDispatcherDiscoveryTest::tool).toList()),
                Duration.ofMinutes(1));
    }

    private static InteropToolRegistry registryWithUnreachableDiscovery() {
        return new InteropToolRegistry(() -> {
            throw new RuntimeException("agentscope unreachable");
        }, Duration.ofMinutes(1));
    }

    @Test
    void proxiesAgentToolThatLiveDiscoveryAdvertises() {
        RecordingAgentClient agent = new RecordingAgentClient();
        InteropToolDispatcher dispatcher = new InteropToolDispatcher(
                agent, registryAdvertising(InteropToolRegistry.AGENT_RUN_TOOL));

        McpToolCallReply reply = dispatcher.dispatch(
                new McpToolCallRequest(InteropToolRegistry.AGENT_RUN_TOOL, Map.of("goal", "summarize")));

        assertThat(reply.success()).isTrue();
        assertThat(agent.calls).isEqualTo(1);
    }

    @Test
    void refusesAgentToolThatLiveDiscoveryDoesNotAdvertise() {
        RecordingAgentClient agent = new RecordingAgentClient();
        InteropToolDispatcher dispatcher = new InteropToolDispatcher(
                agent, registryAdvertising(InteropToolRegistry.AGENT_RUN_TOOL));

        McpToolCallReply reply = dispatcher.dispatch(new McpToolCallRequest(
                InteropToolRegistry.AGENT_DAG_PLAN_RUN_TOOL, Map.of("goal", "build plan")));

        assertThat(reply.success()).isFalse();
        assertThat(reply.error()).isEqualTo("tool is not advertised by AgentScope capability discovery");
        assertThat(agent.calls).isZero();
    }

    @Test
    void refusesEveryAgentToolWhenDiscoveryIsUnreachable() {
        RecordingAgentClient agent = new RecordingAgentClient();
        InteropToolDispatcher dispatcher = new InteropToolDispatcher(
                agent, registryWithUnreachableDiscovery());

        for (String tool : List.of(InteropToolRegistry.AGENT_RUN_TOOL,
                InteropToolRegistry.AGENT_RUN_ASYNC_TOOL,
                InteropToolRegistry.AGENT_DAG_PLAN_RUN_TOOL,
                InteropToolRegistry.AGENT_DAG_PLAN_RUN_ASYNC_TOOL)) {
            McpToolCallReply reply = dispatcher.dispatch(
                    new McpToolCallRequest(tool, Map.of("goal", "summarize")));

            assertThat(reply.success()).as(tool).isFalse();
            assertThat(reply.error()).as(tool)
                    .isEqualTo("tool is not advertised by AgentScope capability discovery");
        }
        assertThat(agent.calls).isZero();
    }

    @Test
    void keepsServingLocalPingWhenDiscoveryIsUnreachable() {
        InteropToolDispatcher dispatcher = new InteropToolDispatcher(
                new RecordingAgentClient(), registryWithUnreachableDiscovery());

        McpToolCallReply reply = dispatcher.dispatch(
                new McpToolCallRequest(InteropToolRegistry.PING_TOOL, Map.of("message", "hello")));

        assertThat(reply.success()).isTrue();
        assertThat(reply.result()).isEqualTo(Map.of("pong", "hello"));
    }

    @Test
    void rejectsToolInteropCannotProxyEvenWhenAdvertised() {
        RecordingAgentClient agent = new RecordingAgentClient();
        InteropToolDispatcher dispatcher = new InteropToolDispatcher(
                agent, registryAdvertising("platform.agent.brand_new"));

        McpToolCallReply reply = dispatcher.dispatch(
                new McpToolCallRequest("platform.agent.brand_new", Map.of("goal", "summarize")));

        assertThat(reply.success()).isFalse();
        assertThat(reply.error()).isEqualTo("unknown tool");
        assertThat(agent.calls).isZero();
    }

    private static final class RecordingAgentClient implements AgentInteropClient {

        private int calls;

        @Override
        public AgentRunReply run(String goal) {
            calls++;
            return new AgentRunReply(goal, List.of(), "done", "finished", 0, "acme");
        }

        @Override
        public Object runAsync(String goal, String webhookUrl) {
            calls++;
            return Map.of("taskId", "task-1");
        }

        @Override
        public Object planDagAndRun(String goal) {
            calls++;
            return Map.of("finalAnswer", "dag-done");
        }

        @Override
        public Object planDagAndRunAsync(String goal, String webhookUrl) {
            calls++;
            return Map.of("taskId", "dag-task-1");
        }
    }
}
