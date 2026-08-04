package com.lrj.platform.interop;

import com.lrj.platform.protocol.interop.AgentCapabilityRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * HttpAgentCapabilityClientTest：用 {@link MockRestServiceServer} 验证
 * {@link HttpAgentCapabilityClient} 从 AgentScope 版本化 registry 拉取能力。
 */
class HttpAgentCapabilityClientTest {

    @Test
    void pullsCapabilitiesFromAgentService() {
        RestTemplate rt = new RestTemplateBuilder().rootUri("http://agent.local").build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        HttpAgentCapabilityClient client = new HttpAgentCapabilityClient(rt);

        server.expect(once(), requestTo("http://agent.local/agent/capabilities/registry"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"schemaVersion":"agent-capability-registry.v1","revision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","capabilities":[
                          {"name":"platform.agent.run","description":"run","inputSchema":{"type":"object"}},
                          {"name":"platform.agent.session.run","description":"session","inputSchema":{"type":"object"}}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        AgentCapabilityRegistry registry = client.discoverRegistry();

        assertThat(registry.schemaVersion()).isEqualTo("agent-capability-registry.v1");
        assertThat(registry.capabilities()).extracting(tool -> tool.name())
                .containsExactly("platform.agent.run", "platform.agent.session.run");
        server.verify();
    }
}
