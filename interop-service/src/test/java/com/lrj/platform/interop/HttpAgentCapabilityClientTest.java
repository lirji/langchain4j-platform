package com.lrj.platform.interop;

import com.lrj.platform.protocol.interop.McpToolDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpAgentCapabilityClientTest {

    @Test
    void pullsCapabilitiesFromAgentService() {
        RestTemplate rt = new RestTemplateBuilder().rootUri("http://agent.local").build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        HttpAgentCapabilityClient client = new HttpAgentCapabilityClient(rt);

        server.expect(once(), requestTo("http://agent.local/agent/capabilities"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          {"name":"platform.agent.run","description":"run","inputSchema":{"type":"object"}},
                          {"name":"platform.agent.run_async","description":"async","inputSchema":{"type":"object"}}
                        ]
                        """, MediaType.APPLICATION_JSON));

        List<McpToolDescriptor> tools = client.discoverTools();

        assertThat(tools).extracting(McpToolDescriptor::name)
                .containsExactly("platform.agent.run", "platform.agent.run_async");
        server.verify();
    }
}
