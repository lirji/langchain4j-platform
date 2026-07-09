package com.lrj.platform.agent.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpWorkflowClientTest {

    @Test
    void startRefund_postsMessageAndParsesWaitingReply() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("/workflow/refund/start"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.message").value("退订单 O123 的 5000 元"))
                .andRespond(withSuccess(
                        "{\"instanceId\":\"pi-1\",\"status\":\"WAITING_APPROVAL\",\"reply\":null,"
                                + "\"taskId\":\"task-9\",\"priority\":\"HIGH\",\"deduplicated\":false}",
                        APPLICATION_JSON));

        WorkflowClient.StartOutcome outcome = new HttpWorkflowClient(restTemplate)
                .startRefund("退订单 O123 的 5000 元");

        server.verify();
        assertThat(outcome.error()).isNull();
        assertThat(outcome.reply().instanceId()).isEqualTo("pi-1");
        assertThat(outcome.reply().status()).isEqualTo("WAITING_APPROVAL");
        assertThat(outcome.reply().taskId()).isEqualTo("task-9");
    }

    @Test
    void startRefund_transportFailureReturnsError() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("/workflow/refund/start")).andRespond(withServerError());

        WorkflowClient.StartOutcome outcome = new HttpWorkflowClient(restTemplate).startRefund("退款");

        assertThat(outcome.reply()).isNull();
        assertThat(outcome.error()).isNotBlank();
    }

    @Test
    void instance_getsAndParsesStatus() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("/workflow/instances/pi-1"))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "{\"instanceId\":\"pi-1\",\"status\":\"COMPLETED\",\"reply\":\"已退款\"}", APPLICATION_JSON));

        WorkflowClient.InstanceOutcome outcome = new HttpWorkflowClient(restTemplate).instance("pi-1");

        server.verify();
        assertThat(outcome.error()).isNull();
        assertThat(outcome.reply().status()).isEqualTo("COMPLETED");
        assertThat(outcome.reply().reply()).isEqualTo("已退款");
    }

    @Test
    void instance_notFoundReturnsError() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("/workflow/instances/missing")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        WorkflowClient.InstanceOutcome outcome = new HttpWorkflowClient(restTemplate).instance("missing");

        assertThat(outcome.reply()).isNull();
        assertThat(outcome.error()).contains("not found");
    }

    @Test
    void tasks_getsAndParsesList() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("/workflow/tasks"))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "[{\"taskId\":\"task-9\",\"name\":\"审批退款\",\"instanceId\":\"pi-1\","
                                + "\"priority\":\"HIGH\",\"summary\":\"退5000\",\"assignee\":null}]",
                        APPLICATION_JSON));

        WorkflowClient.TasksOutcome outcome = new HttpWorkflowClient(restTemplate).tasks();

        server.verify();
        assertThat(outcome.error()).isNull();
        assertThat(outcome.tasks()).hasSize(1);
        assertThat(outcome.tasks().get(0).taskId()).isEqualTo("task-9");
    }

    @Test
    void tasks_forbiddenTranslatesToChinesePermissionNotice() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("/workflow/tasks")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        WorkflowClient.TasksOutcome outcome = new HttpWorkflowClient(restTemplate).tasks();

        assertThat(outcome.tasks()).isEmpty();
        assertThat(outcome.error()).contains("审批权限");
    }
}
