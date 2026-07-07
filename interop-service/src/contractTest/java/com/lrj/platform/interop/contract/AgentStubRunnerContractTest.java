package com.lrj.platform.interop.contract;

import com.lrj.platform.interop.HttpAgentInteropClient;
import com.lrj.platform.protocol.agent.AgentRunReply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties;
import org.springframework.cloud.contract.stubrunner.junit.StubRunnerExtension;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * interop → agent 的 consumer 契约测试：用 agent-service 的 stub jar（LOCAL 模式）启动 WireMock，
 * 验证 {@link HttpAgentInteropClient#run} 会命中 provider 承诺的 /agent/run 形状。
 *
 * <p>用 {@link StubRunnerExtension}（纯 JUnit5 扩展，不起 Spring context）。仅在 {@code -Pcontract} profile 下
 * 编译/运行；运行前需先 {@code mvn -Pcontract -DskipTests install} 让 agent-service 的 stub jar 就位。
 */
class AgentStubRunnerContractTest {

    @RegisterExtension
    static StubRunnerExtension stubRunner = new StubRunnerExtension()
            .downloadStub("com.lrj.platform:agent-service:0.1.0-SNAPSHOT:stubs")
            .stubsMode(StubRunnerProperties.StubsMode.LOCAL);

    @Test
    void runsAgentViaStub() {
        int port = stubRunner.findStubUrl("agent-service").getPort();
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setUriTemplateHandler(new DefaultUriBuilderFactory("http://localhost:" + port));
        HttpAgentInteropClient client = new HttpAgentInteropClient(restTemplate);

        AgentRunReply reply = client.run("summarize refund policy");

        assertThat(reply.goal()).isEqualTo("summarize refund policy");
        assertThat(reply.stopReason()).isEqualTo("DONE");
        assertThat(reply.tenantId()).isEqualTo("acme");
        assertThat(reply.finalAnswer()).isEqualTo("Refunds are issued within 7 days.");
        assertThat(reply.steps()).hasSize(1);
        assertThat(reply.steps().get(0).action()).isEqualTo("rag_search");
    }
}
