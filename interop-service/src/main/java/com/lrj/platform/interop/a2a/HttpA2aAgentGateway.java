package com.lrj.platform.interop.a2a;

import com.lrj.platform.protocol.agent.AgentRunReply;
import com.lrj.platform.protocol.agent.AgentRunRequest;
import com.lrj.platform.protocol.agent.AgentTaskView;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

/**
 * {@link A2aAgentGateway} 的 HTTP 实现，复用装了 tenant/trace forwarder 的
 * {@code interopAgentRestTemplate}（内部 JWT 透传，见 {@code InteropConfig}）。
 */
@Component
public class HttpA2aAgentGateway implements A2aAgentGateway {

    private final RestTemplate interopAgentRestTemplate;

    public HttpA2aAgentGateway(RestTemplate interopAgentRestTemplate) {
        this.interopAgentRestTemplate = interopAgentRestTemplate;
    }

    @Override
    public String chat(String text) {
        AgentRunReply reply = interopAgentRestTemplate.postForObject(
                "/agent/run", new AgentRunRequest(text), AgentRunReply.class);
        return reply == null ? "" : reply.finalAnswer();
    }

    @Override
    public AgentTaskView submitTask(String goal, String webhookUrl) {
        return interopAgentRestTemplate.postForObject(
                "/agent/run/async", new AgentRunRequest(goal, webhookUrl), AgentTaskView.class);
    }

    @Override
    public Optional<AgentTaskView> getTask(String taskId) {
        try {
            return Optional.ofNullable(interopAgentRestTemplate.getForObject(
                    "/agent/tasks/{taskId}", AgentTaskView.class, taskId));
        } catch (HttpClientErrorException.NotFound ex) {
            return Optional.empty();
        }
    }

    @Override
    public boolean cancelTask(String taskId) {
        try {
            interopAgentRestTemplate.delete("/agent/tasks/{taskId}", taskId);
            return true;
        } catch (HttpClientErrorException.NotFound ex) {
            return false;
        }
    }
}
