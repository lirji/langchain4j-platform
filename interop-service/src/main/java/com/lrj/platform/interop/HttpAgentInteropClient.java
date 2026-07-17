package com.lrj.platform.interop;

import com.lrj.platform.protocol.agent.AgentRunReply;
import com.lrj.platform.protocol.agent.AgentRunRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link AgentInteropClient} 的 HTTP 实现：用装了租户/trace 转发器的 {@code interopAgentRestTemplate}
 * （见 {@link InteropConfig}）把请求 POST 到 agent-service 的 {@code /agent/run}、{@code /agent/run/async}、
 * {@code /agent/dag/plan-run} 与 {@code /agent/dag/plan-run/async}。
 */
@Component
public class HttpAgentInteropClient implements AgentInteropClient {

    private final RestTemplate interopAgentRestTemplate;

    public HttpAgentInteropClient(RestTemplate interopAgentRestTemplate) {
        this.interopAgentRestTemplate = interopAgentRestTemplate;
    }

    @Override
    public AgentRunReply run(String goal) {
        return interopAgentRestTemplate.postForObject("/agent/run", new AgentRunRequest(goal), AgentRunReply.class);
    }

    @Override
    public Object runAsync(String goal, String webhookUrl) {
        return interopAgentRestTemplate.postForObject("/agent/run/async", new AgentRunRequest(goal, webhookUrl), Object.class);
    }

    @Override
    public Object planDagAndRun(String goal) {
        return interopAgentRestTemplate.postForObject("/agent/dag/plan-run", Map.of("goal", goal), Object.class);
    }

    @Override
    public Object planDagAndRunAsync(String goal, String webhookUrl) {
        Map<String, String> request = new HashMap<>();
        request.put("goal", goal);
        if (webhookUrl != null && !webhookUrl.isBlank()) {
            request.put("webhookUrl", webhookUrl);
        }
        return interopAgentRestTemplate.postForObject("/agent/dag/plan-run/async", request, Object.class);
    }
}
