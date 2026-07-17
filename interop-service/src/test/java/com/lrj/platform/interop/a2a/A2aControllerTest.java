package com.lrj.platform.interop.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.interop.InteropProperties;
import com.lrj.platform.protocol.agent.AgentTaskView;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A2aControllerTest：验证 {@link A2aController} 的 A2A JSON-RPC 入口——well-known Agent Card、
 * {@code message/send} 路由到 gateway、{@code message/stream} 返回 {@link SseEmitter}，
 * 以及空文本与缺 method 时的错误码。
 */
class A2aControllerTest {

    private final ObjectMapper json = new ObjectMapper();

    private A2aController controller(A2aAgentGateway gateway) {
        A2aTaskMapper mapper = new A2aTaskMapper();
        A2aService service = new A2aService(
                gateway, mapper, new InteropProperties(), json, new A2aPushNotificationStore());
        A2aStreamService streamService = new A2aStreamService(
                (chatId, message, onToken, onDone, onError) -> onDone.run(),
                (taskId, onUpdate, onDone, onError) -> onDone.run(),
                gateway, mapper, json, Runnable::run);
        return new A2aController(service, streamService, json);
    }

    @Test
    void wellKnownAgentCardReturnsA2aCard() {
        A2aAgentCard card = controller(new FakeGateway()).wellKnownAgentCard();

        assertThat(card.name()).isNotBlank();
        assertThat(card.skills()).extracting(A2aAgentCard.Skill::id)
                .contains(A2aService.SKILL_CHAT, A2aService.SKILL_RESEARCH);
    }

    @Test
    void handleRoutesMessageSendToGateway() {
        FakeGateway gateway = new FakeGateway();
        var body = json.valueToTree(Map.of(
                "jsonrpc", "2.0",
                "id", 7,
                "method", "message/send",
                "params", Map.of("message", Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("kind", "text", "text", "hello"))))));

        JsonRpcResponse response = (JsonRpcResponse) controller(gateway).handle(body);

        assertThat(response.id()).isEqualTo(7L);
        assertThat(gateway.lastChat).isEqualTo("hello");
        assertThat(response.result()).isInstanceOf(A2aMessage.class);
    }

    @Test
    void handleReturnsSseEmitterForMessageStream() {
        var body = json.valueToTree(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "message/stream",
                "params", Map.of("message", Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("kind", "text", "text", "hello"))))));

        Object result = controller(new FakeGateway()).handle(body);

        assertThat(result).isInstanceOf(SseEmitter.class);
    }

    @Test
    void handleRejectsBlankMessageStream() {
        var body = json.valueToTree(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "message/stream",
                "params", Map.of("message", Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("kind", "text", "text", "   "))))));

        Object result = controller(new FakeGateway()).handle(body);

        assertThat(result).isInstanceOf(JsonRpcResponse.class);
        assertThat(((JsonRpcResponse) result).error().code()).isEqualTo(JsonRpcError.INVALID_PARAMS);
    }

    @Test
    void handleRejectsMissingMethod() {
        var body = json.valueToTree(Map.of("jsonrpc", "2.0", "id", "abc"));

        JsonRpcResponse response = (JsonRpcResponse) controller(new FakeGateway()).handle(body);

        assertThat(response.id()).isEqualTo("abc");
        assertThat(response.error().code()).isEqualTo(JsonRpcError.INVALID_REQUEST);
    }

    private static class FakeGateway implements A2aAgentGateway {
        private String lastChat;

        @Override
        public String chat(String text) {
            this.lastChat = text;
            return "reply:" + text;
        }

        @Override
        public AgentTaskView submitTask(String goal, String webhookUrl) {
            return new AgentTaskView("task-1", "acme", "alice", "PENDING",
                    Map.of(), null, null, null, null, null);
        }

        @Override
        public Optional<AgentTaskView> getTask(String taskId) {
            return Optional.empty();
        }

        @Override
        public boolean cancelTask(String taskId) {
            return false;
        }
    }
}
