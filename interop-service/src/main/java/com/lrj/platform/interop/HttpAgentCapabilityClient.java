package com.lrj.platform.interop;

import com.lrj.platform.protocol.interop.McpToolDescriptor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * {@link AgentCapabilityClient} 的 HTTP 实现，复用装了 tenant/trace forwarder 的
 * {@code interopAgentRestTemplate}（内部 JWT 透传，见 {@link InteropConfig}）。
 */
public class HttpAgentCapabilityClient implements AgentCapabilityClient {

    private static final ParameterizedTypeReference<List<McpToolDescriptor>> LIST_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestTemplate interopAgentRestTemplate;

    public HttpAgentCapabilityClient(RestTemplate interopAgentRestTemplate) {
        this.interopAgentRestTemplate = interopAgentRestTemplate;
    }

    @Override
    public List<McpToolDescriptor> discoverTools() {
        ResponseEntity<List<McpToolDescriptor>> response = interopAgentRestTemplate.exchange(
                "/agent/capabilities", HttpMethod.GET, null, LIST_TYPE);
        List<McpToolDescriptor> body = response.getBody();
        return body == null ? List.of() : body;
    }
}
