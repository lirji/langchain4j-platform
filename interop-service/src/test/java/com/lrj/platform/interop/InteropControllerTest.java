package com.lrj.platform.interop;

import com.lrj.platform.protocol.agent.AgentRunReply;
import com.lrj.platform.protocol.interop.McpToolCallReply;
import com.lrj.platform.protocol.interop.McpToolCallRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InteropControllerTest {

    @Test
    void exposesAgentCard() {
        InteropController controller = controller();

        assertThat(controller.agentCard().capabilities()).contains("mcp.tools.list");
        assertThat(controller.agentCard().capabilities()).contains("platform.agent.run");
    }

    @Test
    void callsScaffoldPingTool() {
        InteropController controller = controller();

        var response = controller.call(new McpToolCallRequest("platform.ping", Map.of("message", "hello")));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        McpToolCallReply body = (McpToolCallReply) response.getBody();
        assertThat(body.success()).isTrue();
        assertThat(body.result()).isEqualTo(Map.of("pong", "hello"));
    }

    @Test
    void listsAgentRunTool() {
        InteropController controller = controller();

        assertThat(controller.tools())
                .extracting("name")
                .contains(
                        "platform.agent.run",
                        "platform.agent.run_async",
                        "platform.agent.dag.plan_run",
                        "platform.agent.dag.plan_run_async");
    }

    @Test
    void callsAgentRunTool() {
        FakeAgentClient agent = new FakeAgentClient();
        InteropController controller = controller(agent);

        var response = controller.call(new McpToolCallRequest("platform.agent.run", Map.of("goal", "summarize")));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(agent.lastGoal).isEqualTo("summarize");
        McpToolCallReply body = (McpToolCallReply) response.getBody();
        assertThat(body.success()).isTrue();
        assertThat(body.result()).isInstanceOf(AgentRunReply.class);
    }

    @Test
    void rejectsAgentRunWithoutGoal() {
        InteropController controller = controller();

        var response = controller.call(new McpToolCallRequest("platform.agent.run", Map.of()));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        McpToolCallReply body = (McpToolCallReply) response.getBody();
        assertThat(body.error()).isEqualTo("goal is required");
    }

    @Test
    void callsAgentRunAsyncTool() {
        FakeAgentClient agent = new FakeAgentClient();
        InteropController controller = controller(agent);

        var response = controller.call(new McpToolCallRequest("platform.agent.run_async", Map.of(
                "goal", "summarize",
                "webhookUrl", "http://callback.local/task")));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(agent.lastGoal).isEqualTo("summarize");
        assertThat(agent.lastWebhookUrl).isEqualTo("http://callback.local/task");
        McpToolCallReply body = (McpToolCallReply) response.getBody();
        assertThat(body.result()).isEqualTo(Map.of("taskId", "task-1"));
    }

    @Test
    void callsAgentDagPlanRunTool() {
        FakeAgentClient agent = new FakeAgentClient();
        InteropController controller = controller(agent);

        var response = controller.call(new McpToolCallRequest("platform.agent.dag.plan_run", Map.of("goal", "build plan")));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(agent.lastGoal).isEqualTo("build plan");
        McpToolCallReply body = (McpToolCallReply) response.getBody();
        assertThat(body.result()).isEqualTo(Map.of("finalAnswer", "dag-done"));
    }

    @Test
    void callsAgentDagPlanRunAsyncTool() {
        FakeAgentClient agent = new FakeAgentClient();
        InteropController controller = controller(agent);

        var response = controller.call(new McpToolCallRequest("platform.agent.dag.plan_run_async", Map.of("goal", "build plan")));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(agent.lastGoal).isEqualTo("build plan");
        McpToolCallReply body = (McpToolCallReply) response.getBody();
        assertThat(body.result()).isEqualTo(Map.of("taskId", "dag-task-1"));
    }

    private InteropController controller() {
        return controller(new FakeAgentClient());
    }

    private InteropController controller(AgentInteropClient agentClient) {
        return new InteropController(
                new InteropToolRegistry(),
                new InteropToolDispatcher(agentClient));
    }

    private static class FakeAgentClient implements AgentInteropClient {

        private String lastGoal;
        private String lastWebhookUrl;

        @Override
        public AgentRunReply run(String goal) {
            this.lastGoal = goal;
            return new AgentRunReply(goal, List.of(), "done", "finished", 0, "acme");
        }

        @Override
        public Object runAsync(String goal, String webhookUrl) {
            this.lastGoal = goal;
            this.lastWebhookUrl = webhookUrl;
            return Map.of("taskId", "task-1");
        }

        @Override
        public Object planDagAndRun(String goal) {
            this.lastGoal = goal;
            return Map.of("finalAnswer", "dag-done");
        }

        @Override
        public Object planDagAndRunAsync(String goal, String webhookUrl) {
            this.lastGoal = goal;
            this.lastWebhookUrl = webhookUrl;
            return Map.of("taskId", "dag-task-1");
        }
    }
}
