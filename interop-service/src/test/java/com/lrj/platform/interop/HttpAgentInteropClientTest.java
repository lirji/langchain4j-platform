package com.lrj.platform.interop;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * HttpAgentInteropClientTest：用 {@link MockRestServiceServer} 验证 {@link HttpAgentInteropClient}
 * 对 agent-service 各端点（run、run/async、dag/plan-run[/async]）的请求路径与请求体是否正确。
 */
class HttpAgentInteropClientTest {

    @Test
    void postsAgentRunRequest() {
        RestTemplate restTemplate = new RestTemplateBuilder().rootUri("http://agent.local").build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        HttpAgentInteropClient client = new HttpAgentInteropClient(restTemplate);

        server.expect(once(), requestTo("http://agent.local/agent/run"))
                .andExpect(content().json("{\"goal\":\"summarize\"}"))
                .andRespond(withSuccess("""
                        {
                          "goal": "summarize",
                          "steps": [],
                          "finalAnswer": "done",
                          "stopReason": "finished",
                          "depth": 0,
                          "tenantId": "acme"
                        }
                        """, MediaType.APPLICATION_JSON));

        var reply = client.run("summarize");

        assertThat(reply.finalAnswer()).isEqualTo("done");
        server.verify();
    }

    @Test
    void postsAgentRunAsyncRequest() {
        RestTemplate restTemplate = new RestTemplateBuilder().rootUri("http://agent.local").build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        HttpAgentInteropClient client = new HttpAgentInteropClient(restTemplate);

        server.expect(once(), requestTo("http://agent.local/agent/run/async"))
                .andExpect(content().json("{\"goal\":\"summarize\",\"webhookUrl\":\"http://callback.local/task\"}"))
                .andRespond(withSuccess("{\"taskId\":\"task-1\"}", MediaType.APPLICATION_JSON));

        Object reply = client.runAsync("summarize", "http://callback.local/task");

        assertThat(reply).isInstanceOf(java.util.Map.class);
        server.verify();
    }

    @Test
    void postsAgentDagPlanRunRequest() {
        RestTemplate restTemplate = new RestTemplateBuilder().rootUri("http://agent.local").build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        HttpAgentInteropClient client = new HttpAgentInteropClient(restTemplate);

        server.expect(once(), requestTo("http://agent.local/agent/dag/plan-run"))
                .andExpect(content().json("{\"goal\":\"build plan\"}"))
                .andRespond(withSuccess("{\"goal\":\"build plan\"}", MediaType.APPLICATION_JSON));

        Object reply = client.planDagAndRun("build plan");

        assertThat(reply).isInstanceOf(java.util.Map.class);
        server.verify();
    }

    @Test
    void postsAgentDagPlanRunAsyncRequest() {
        RestTemplate restTemplate = new RestTemplateBuilder().rootUri("http://agent.local").build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        HttpAgentInteropClient client = new HttpAgentInteropClient(restTemplate);

        server.expect(once(), requestTo("http://agent.local/agent/dag/plan-run/async"))
                .andExpect(content().json("{\"goal\":\"build plan\"}"))
                .andRespond(withSuccess("{\"taskId\":\"dag-task-1\"}", MediaType.APPLICATION_JSON));

        Object reply = client.planDagAndRunAsync("build plan", null);

        assertThat(reply).isInstanceOf(java.util.Map.class);
        server.verify();
    }
}
