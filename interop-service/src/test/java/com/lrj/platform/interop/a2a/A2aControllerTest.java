package com.lrj.platform.interop.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.interop.InteropProperties;
import com.lrj.platform.protocol.agent.AgentTaskView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class A2aControllerTest {

    private final ObjectMapper json = new ObjectMapper();

    private A2aController controller(A2aAgentGateway gateway) {
        A2aService service = new A2aService(gateway, new A2aTaskMapper(), new InteropProperties(), json);
        return new A2aController(service);
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

        JsonRpcResponse response = controller(gateway).handle(body);

        assertThat(response.id()).isEqualTo(7L);
        assertThat(gateway.lastChat).isEqualTo("hello");
        assertThat(response.result()).isInstanceOf(A2aMessage.class);
    }

    @Test
    void handleRejectsMissingMethod() {
        var body = json.valueToTree(Map.of("jsonrpc", "2.0", "id", "abc"));

        JsonRpcResponse response = controller(new FakeGateway()).handle(body);

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
