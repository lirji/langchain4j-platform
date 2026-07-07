package com.lrj.platform.interop.a2a;

import com.lrj.platform.protocol.agent.AgentTaskView;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpA2aAgentGatewayTest {

    private RestTemplate restTemplate() {
        return new RestTemplateBuilder().rootUri("http://agent.local").build();
    }

    @Test
    void chatProxiesToAgentRun() {
        RestTemplate rt = restTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        HttpA2aAgentGateway gateway = new HttpA2aAgentGateway(rt);

        server.expect(once(), requestTo("http://agent.local/agent/run"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"goal\":\"hi\"}"))
                .andRespond(withSuccess("""
                        {"goal":"hi","steps":[],"finalAnswer":"the answer","stopReason":"finished","depth":0,"tenantId":"acme"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(gateway.chat("hi")).isEqualTo("the answer");
        server.verify();
    }

    @Test
    void submitTaskProxiesToRunAsync() {
        RestTemplate rt = restTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        HttpA2aAgentGateway gateway = new HttpA2aAgentGateway(rt);

        server.expect(once(), requestTo("http://agent.local/agent/run/async"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"goal\":\"research\",\"webhookUrl\":\"http://cb.local\"}"))
                .andRespond(withSuccess("""
                        {"taskId":"task-1","tenantId":"acme","userId":"alice","status":"PENDING"}
                        """, MediaType.APPLICATION_JSON));

        AgentTaskView task = gateway.submitTask("research", "http://cb.local");

        assertThat(task.taskId()).isEqualTo("task-1");
        assertThat(task.status()).isEqualTo("PENDING");
        server.verify();
    }

    @Test
    void getTaskReturnsPresentOnSuccess() {
        RestTemplate rt = restTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        HttpA2aAgentGateway gateway = new HttpA2aAgentGateway(rt);

        server.expect(once(), requestTo("http://agent.local/agent/tasks/task-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"taskId":"task-1","status":"SUCCEEDED","result":{"finalAnswer":"done"}}
                        """, MediaType.APPLICATION_JSON));

        Optional<AgentTaskView> task = gateway.getTask("task-1");

        assertThat(task).isPresent();
        assertThat(task.get().status()).isEqualTo("SUCCEEDED");
        server.verify();
    }

    @Test
    void getTaskReturnsEmptyOn404() {
        RestTemplate rt = restTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        HttpA2aAgentGateway gateway = new HttpA2aAgentGateway(rt);

        server.expect(once(), requestTo("http://agent.local/agent/tasks/missing"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(gateway.getTask("missing")).isEmpty();
        server.verify();
    }

    @Test
    void cancelTaskReturnsTrueOnSuccessFalseOn404() {
        RestTemplate rt = restTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        HttpA2aAgentGateway gateway = new HttpA2aAgentGateway(rt);

        server.expect(once(), requestTo("http://agent.local/agent/tasks/task-1"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{\"taskId\":\"task-1\",\"cancelled\":true}", MediaType.APPLICATION_JSON));

        assertThat(gateway.cancelTask("task-1")).isTrue();
        server.verify();

        RestTemplate rt2 = restTemplate();
        MockRestServiceServer server2 = MockRestServiceServer.bindTo(rt2).build();
        HttpA2aAgentGateway gateway2 = new HttpA2aAgentGateway(rt2);
        server2.expect(once(), requestTo("http://agent.local/agent/tasks/missing"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(gateway2.cancelTask("missing")).isFalse();
        server2.verify();
    }
}
