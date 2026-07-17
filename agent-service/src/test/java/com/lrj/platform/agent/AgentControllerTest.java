package com.lrj.platform.agent;

import com.lrj.platform.protocol.agent.AgentRunRequest;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentControllerTest：验证 {@link AgentController} 把 {@link DeepAgentService} 的运行结果映射为
 * 带租户 id 的协议回复，并对空 goal 返回 400。
 */
class AgentControllerTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void runReturnsProtocolReplyWithTenant() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        DeepAgentService service = new DeepAgentService(
                (goal, actions, scratchpad, history) -> new AgentDecision("", "finish", "", "", "ok"),
                List.of(),
                new AgentProperties());
        AgentController controller = new AgentController(service);

        var response = controller.run(new AgentRunRequest("goal"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).hasFieldOrPropertyWithValue("finalAnswer", "ok");
        assertThat(response.getBody()).hasFieldOrPropertyWithValue("tenantId", "acme");
    }

    @Test
    void blankGoalReturnsBadRequest() {
        AgentController controller = new AgentController(new DeepAgentService(
                (goal, actions, scratchpad, history) -> new AgentDecision("", "finish", "", "", "ok"),
                List.of(),
                new AgentProperties()));

        var response = controller.run(new AgentRunRequest(" "));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
