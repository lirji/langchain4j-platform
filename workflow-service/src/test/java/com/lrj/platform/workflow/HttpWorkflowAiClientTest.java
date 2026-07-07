package com.lrj.platform.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * MockRestServiceServer 单测：HttpWorkflowAiClient 对 conversation-service 的调用。
 * 核心校验 B9 铁律——HTTP 失败必须<b>抛异常</b>（交 ServiceTaskDelegates 的 withRetry 降级），绝不静默吞成空/兜底。
 */
class HttpWorkflowAiClientTest {

    private static final String BASE = "http://conversation";

    private RestTemplate restTemplate() {
        return new RestTemplateBuilder().rootUri(BASE).build();
    }

    @Test
    void resolveReply_success_returnsReply() {
        RestTemplate rt = restTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(rt);
        server.expect(requestTo(BASE + "/conversation/workflow/reply"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("{\"reply\":\"您的退款将尽快处理。\"}", MediaType.APPLICATION_JSON));

        String reply = new HttpWorkflowAiClient(rt).resolveReply("acme:c1", "退款没到账");

        assertThat(reply).isEqualTo("您的退款将尽快处理。");
        server.verify();
    }

    @Test
    void resolveReply_serverError_throws() {
        RestTemplate rt = restTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(rt);
        server.expect(requestTo(BASE + "/conversation/workflow/reply"))
                .andRespond(withServerError());

        HttpWorkflowAiClient client = new HttpWorkflowAiClient(rt);
        assertThatThrownBy(() -> client.resolveReply("acme:c1", "退款没到账"))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    void resolveReply_blankBody_throws() {
        RestTemplate rt = restTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(rt);
        server.expect(requestTo(BASE + "/conversation/workflow/reply"))
                .andRespond(withSuccess("{\"reply\":\"  \"}", MediaType.APPLICATION_JSON));

        HttpWorkflowAiClient client = new HttpWorkflowAiClient(rt);
        assertThatThrownBy(() -> client.resolveReply("acme:c1", "x"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void extractTicket_success_mapsPriority() {
        RestTemplate rt = restTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(rt);
        server.expect(requestTo(BASE + "/conversation/workflow/ticket"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"title\":\"退款请求\",\"priority\":\"HIGH\",\"category\":\"refund\",\"summary\":\"一直不到账\",\"tags\":[\"投诉\"]}",
                        MediaType.APPLICATION_JSON));

        Ticket t = new HttpWorkflowAiClient(rt).extractTicket("一直不到账");

        assertThat(t.priority()).isEqualTo(Ticket.Priority.HIGH);
        assertThat(t.category()).isEqualTo("refund");
        assertThat(t.tags()).containsExactly("投诉");
        server.verify();
    }

    @Test
    void extractTicket_serverError_throws() {
        RestTemplate rt = restTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(rt);
        server.expect(requestTo(BASE + "/conversation/workflow/ticket"))
                .andRespond(withServerError());

        HttpWorkflowAiClient client = new HttpWorkflowAiClient(rt);
        assertThatThrownBy(() -> client.extractTicket("x"))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    void mapPriority_unknownOrNull_defaultsToHigh() {
        // 从严：抽不出/无法识别优先级时取 HIGH（转人工），绝不默认 LOW 放过高风险退款。
        assertThat(HttpWorkflowAiClient.mapPriority(null)).isEqualTo(Ticket.Priority.HIGH);
        assertThat(HttpWorkflowAiClient.mapPriority("")).isEqualTo(Ticket.Priority.HIGH);
        assertThat(HttpWorkflowAiClient.mapPriority("banana")).isEqualTo(Ticket.Priority.HIGH);
        assertThat(HttpWorkflowAiClient.mapPriority("low")).isEqualTo(Ticket.Priority.LOW);
        assertThat(HttpWorkflowAiClient.mapPriority(" critical ")).isEqualTo(Ticket.Priority.CRITICAL);
    }
}
