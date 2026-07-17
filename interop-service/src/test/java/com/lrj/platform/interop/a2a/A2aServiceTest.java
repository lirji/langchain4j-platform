package com.lrj.platform.interop.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.interop.InteropProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * A2aServiceTest：验证 {@link A2aService} 的 A2A JSON-RPC 分派——{@code message/send} 按 skill 代理到
 * agent-service 的同步 chat 或异步 research 任务、push 通知配置登记与 webhook 回指 interop 自身、
 * {@code tasks/get} 与 {@code tasks/cancel} 的状态映射及错误码，以及 Agent Card 暴露。
 */
class A2aServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private A2aService service;
    private A2aPushNotificationStore pushStore;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplateBuilder().rootUri("http://agent.local").build();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        pushStore = new A2aPushNotificationStore();
        service = new A2aService(
                new HttpA2aAgentGateway(restTemplate),
                new A2aTaskMapper(),
                new InteropProperties(),
                json,
                pushStore);
    }

    private JsonNode params(Object value) {
        return json.valueToTree(value);
    }

    private JsonNode textMessageParams(String text, String skill) {
        Map<String, Object> message = new java.util.LinkedHashMap<>();
        message.put("role", "user");
        message.put("parts", List.of(Map.of("kind", "text", "text", text)));
        if (skill != null) {
            message.put("metadata", Map.of("skill", skill));
        }
        return params(Map.of("message", message));
    }

    @Test
    void messageSendChatProxiesToAgentRunAndReturnsMessage() {
        server.expect(once(), requestTo("http://agent.local/agent/run"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"goal":"hi","steps":[],"finalAnswer":"the answer","stopReason":"finished","depth":0,"tenantId":"acme"}
                        """, MediaType.APPLICATION_JSON));

        JsonRpcResponse response = service.dispatch("message/send", textMessageParams("hi", null), "1");

        assertThat(response.error()).isNull();
        assertThat(response.result()).isInstanceOf(A2aMessage.class);
        A2aMessage msg = (A2aMessage) response.result();
        assertThat(msg.role()).isEqualTo("agent");
        assertThat(msg.textContent()).isEqualTo("the answer");
        server.verify();
    }

    @Test
    void messageSendResearchSkillProxiesToRunAsyncAndReturnsTask() {
        server.expect(once(), requestTo("http://agent.local/agent/run/async"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"taskId":"task-1","tenantId":"acme","userId":"alice","status":"PENDING"}
                        """, MediaType.APPLICATION_JSON));

        JsonRpcResponse response = service.dispatch(
                "message/send", textMessageParams("do research", A2aService.SKILL_RESEARCH), "1");

        assertThat(response.error()).isNull();
        assertThat(response.result()).isInstanceOf(A2aTask.class);
        A2aTask task = (A2aTask) response.result();
        assertThat(task.id()).isEqualTo("task-1");
        assertThat(task.status().state()).isEqualTo(TaskState.SUBMITTED);
        server.verify();
    }

    @Test
    void messageSendResearchWithPushConfigRegistersAndRoutesWebhookToInterop() {
        server.expect(once(), requestTo("http://agent.local/agent/run/async"))
                .andExpect(method(HttpMethod.POST))
                // webhook 指向 interop 自己的回调（而非客户端 URL），终态由 forwarder 中继
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .jsonPath("$.webhookUrl").value("http://localhost:8088/interop/a2a/push-callback"))
                .andRespond(withSuccess("""
                        {"taskId":"task-9","tenantId":"acme","status":"PENDING"}
                        """, MediaType.APPLICATION_JSON));

        Map<String, Object> message = new java.util.LinkedHashMap<>();
        message.put("role", "user");
        message.put("parts", List.of(Map.of("kind", "text", "text", "do research")));
        message.put("metadata", Map.of("skill", A2aService.SKILL_RESEARCH));
        JsonNode params = params(Map.of(
                "message", message,
                "configuration", Map.of("pushNotificationConfig",
                        Map.of("url", "https://client/hook", "token", "tok-1"))));

        JsonRpcResponse response = service.dispatch("message/send", params, "1");

        assertThat(response.error()).isNull();
        // 客户端 push 配置按 (tenant, taskId) 登记；测试无租户 → anonymous
        assertThat(pushStore.get("anonymous", "task-9")).isPresent();
        assertThat(pushStore.get("anonymous", "task-9").get().url()).isEqualTo("https://client/hook");
        server.verify();
    }

    @Test
    void pushConfigSetThenGetRoundTrips() {
        JsonRpcResponse set = service.dispatch("tasks/pushNotificationConfig/set",
                params(Map.of("taskId", "t9",
                        "pushNotificationConfig", Map.of("url", "https://c/h", "token", "tk"))), "1");
        assertThat(set.error()).isNull();
        assertThat(pushStore.get("anonymous", "t9")).isPresent();

        JsonRpcResponse get = service.dispatch("tasks/pushNotificationConfig/get",
                params(Map.of("id", "t9")), "2");
        assertThat(get.error()).isNull();
        assertThat(get.result()).isInstanceOf(TaskPushNotificationConfig.class);
        TaskPushNotificationConfig cfg = (TaskPushNotificationConfig) get.result();
        assertThat(cfg.pushNotificationConfig().url()).isEqualTo("https://c/h");
    }

    @Test
    void pushConfigGetReturnsNotFoundWhenAbsent() {
        JsonRpcResponse get = service.dispatch("tasks/pushNotificationConfig/get",
                params(Map.of("id", "missing")), "1");
        assertThat(get.result()).isNull();
        assertThat(get.error().code()).isEqualTo(JsonRpcError.TASK_NOT_FOUND);
    }

    @Test
    void messageSendRejectsBlankText() {
        JsonRpcResponse response = service.dispatch("message/send", textMessageParams("   ", null), "1");

        assertThat(response.result()).isNull();
        assertThat(response.error().code()).isEqualTo(JsonRpcError.INVALID_PARAMS);
    }

    @Test
    void tasksGetMapsAgentTaskToA2aTask() {
        server.expect(once(), requestTo("http://agent.local/agent/tasks/task-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"taskId":"task-1","status":"SUCCEEDED","result":{"finalAnswer":"done"},"updatedAt":"2026-07-07T00:01:00Z"}
                        """, MediaType.APPLICATION_JSON));

        JsonRpcResponse response = service.dispatch("tasks/get", params(Map.of("id", "task-1")), "1");

        assertThat(response.error()).isNull();
        A2aTask task = (A2aTask) response.result();
        assertThat(task.status().state()).isEqualTo(TaskState.COMPLETED);
        assertThat(task.artifacts().get(0).parts().get(0).text()).isEqualTo("done");
        server.verify();
    }

    @Test
    void tasksGetReturnsTaskNotFoundOn404() {
        server.expect(once(), requestTo("http://agent.local/agent/tasks/missing"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        JsonRpcResponse response = service.dispatch("tasks/get", params(Map.of("id", "missing")), "1");

        assertThat(response.result()).isNull();
        assertThat(response.error().code()).isEqualTo(JsonRpcError.TASK_NOT_FOUND);
        server.verify();
    }

    @Test
    void tasksCancelRejectsTerminalTask() {
        server.expect(once(), requestTo("http://agent.local/agent/tasks/task-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"taskId":"task-1","status":"SUCCEEDED"}
                        """, MediaType.APPLICATION_JSON));

        JsonRpcResponse response = service.dispatch("tasks/cancel", params(Map.of("id", "task-1")), "1");

        assertThat(response.result()).isNull();
        assertThat(response.error().code()).isEqualTo(JsonRpcError.TASK_NOT_CANCELABLE);
        server.verify();
    }

    @Test
    void tasksCancelProxiesDeleteForRunningTask() {
        server.expect(once(), requestTo("http://agent.local/agent/tasks/task-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"taskId\":\"task-1\",\"status\":\"RUNNING\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://agent.local/agent/tasks/task-1"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{\"taskId\":\"task-1\",\"cancelled\":true}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://agent.local/agent/tasks/task-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"taskId\":\"task-1\",\"status\":\"CANCELLED\"}", MediaType.APPLICATION_JSON));

        JsonRpcResponse response = service.dispatch("tasks/cancel", params(Map.of("id", "task-1")), "1");

        assertThat(response.error()).isNull();
        A2aTask task = (A2aTask) response.result();
        assertThat(task.status().state()).isEqualTo(TaskState.CANCELED);
        server.verify();
    }

    @Test
    void unknownMethodReturnsMethodNotFound() {
        JsonRpcResponse response = service.dispatch("bogus/method", null, "1");

        assertThat(response.error().code()).isEqualTo(JsonRpcError.METHOD_NOT_FOUND);
    }

    @Test
    void agentCardExposesChatAndResearchSkills() {
        A2aAgentCard card = service.agentCard();

        assertThat(card.skills()).extracting(A2aAgentCard.Skill::id)
                .containsExactly(A2aService.SKILL_CHAT, A2aService.SKILL_RESEARCH);
        assertThat(card.url()).endsWith("/interop/a2a");
        assertThat(card.securitySchemes()).containsKey("apiKey");
    }
}
