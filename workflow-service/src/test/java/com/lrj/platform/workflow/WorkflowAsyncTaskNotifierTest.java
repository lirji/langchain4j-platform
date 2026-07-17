package com.lrj.platform.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * WorkflowAsyncTaskNotifierTest：借助 {@link org.springframework.test.web.client.MockRestServiceServer}
 * 验证 {@link WorkflowAsyncTaskNotifier#publishTerminal} 先 POST 创建异步任务、再 PATCH 置为 SUCCEEDED 的两步回推，
 * 以及创建返回 409 冲突时仍能幂等地完成既有任务。
 */
class WorkflowAsyncTaskNotifierTest {

    @Test
    void publishTerminalCreatesAndCompletesAsyncTask() {
        RestTemplate restTemplate = new RestTemplateBuilder().rootUri("http://async-task").build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        WorkflowAsyncTaskNotifier notifier = new WorkflowAsyncTaskNotifier(restTemplate, new WorkflowProperties());

        server.expect(requestTo("http://async-task/async/tasks"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.taskId").value("workflow-pi-1"))
                .andExpect(jsonPath("$.kind").value("workflow.terminal"))
                .andExpect(jsonPath("$.webhookUrl").value("http://callback.local/workflow"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://async-task/async/tasks/workflow-pi-1/status"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.result.instanceId").value("pi-1"))
                .andRespond(withSuccess());

        assertThat(notifier.publishTerminal("pi-1", "acme", "http://callback.local/workflow", Map.of("answer", "ok")))
                .isTrue();
        server.verify();
    }

    @Test
    void publishTerminalCompletesExistingAsyncTaskWhenCreateConflicts() {
        RestTemplate restTemplate = new RestTemplateBuilder().rootUri("http://async-task").build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        WorkflowAsyncTaskNotifier notifier = new WorkflowAsyncTaskNotifier(restTemplate, new WorkflowProperties());

        server.expect(requestTo("http://async-task/async/tasks"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(requestTo("http://async-task/async/tasks/workflow-pi-1/status"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andRespond(withSuccess());

        assertThat(notifier.publishTerminal("pi-1", "acme", "http://callback.local/workflow", "done"))
                .isTrue();
        server.verify();
    }
}
