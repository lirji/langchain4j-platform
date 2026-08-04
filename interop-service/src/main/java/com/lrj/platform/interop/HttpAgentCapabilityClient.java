package com.lrj.platform.interop;

import com.lrj.platform.protocol.interop.AgentCapabilityRegistry;
import org.springframework.web.client.RestTemplate;

/**
 * {@link AgentCapabilityClient} 的 HTTP 实现，复用装了 tenant/trace forwarder 的
 * {@code interopAgentRestTemplate}（内部 JWT 透传，见 {@link InteropConfig}）。
 */
public class HttpAgentCapabilityClient implements AgentCapabilityClient {

    private final RestTemplate interopAgentRestTemplate;

    public HttpAgentCapabilityClient(RestTemplate interopAgentRestTemplate) {
        this.interopAgentRestTemplate = interopAgentRestTemplate;
    }

    @Override
    public AgentCapabilityRegistry discoverRegistry() {
        AgentCapabilityRegistry registry = interopAgentRestTemplate.getForObject(
                "/agent/capabilities/registry", AgentCapabilityRegistry.class);
        return registry == null
                ? new AgentCapabilityRegistry("agent-capability-registry.v1", "", java.util.List.of())
                : registry;
    }
}
