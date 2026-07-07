package com.lrj.platform.conversation.contract;

import com.lrj.platform.conversation.HttpKnowledgeClient;
import com.lrj.platform.conversation.KnowledgeClient;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties;
import org.springframework.cloud.contract.stubrunner.junit.StubRunnerExtension;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * conversation → knowledge 的 consumer 契约测试：用 knowledge-service 发布到本地 .m2 的 stub jar
 * （LOCAL 模式）启动 WireMock，验证 {@link HttpKnowledgeClient} 真的会命中 provider 承诺的形状。
 *
 * <p>用 {@link StubRunnerExtension}（纯 JUnit5 扩展，不起 Spring context），贴合本仓单测文化。
 * 仅在 {@code -Pcontract} profile 下编译/运行；运行前需先 {@code mvn -Pcontract -DskipTests install}
 * 让 knowledge-service 的 stub jar 就位。
 */
class KnowledgeStubRunnerContractTest {

    @RegisterExtension
    static StubRunnerExtension stubRunner = new StubRunnerExtension()
            .downloadStub("com.lrj.platform:knowledge-service:0.1.0-SNAPSHOT:stubs")
            .stubsMode(StubRunnerProperties.StubsMode.LOCAL);

    @Test
    void queriesKnowledgeViaStub() {
        int port = stubRunner.findStubUrl("knowledge-service").getPort();
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setUriTemplateHandler(new DefaultUriBuilderFactory("http://localhost:" + port));
        KnowledgeClient client = new HttpKnowledgeClient(restTemplate);

        KnowledgeQueryReply reply = client.query(new KnowledgeQueryRequest("refund policy", 3, 0.2, "manual"));

        assertThat(reply.query()).isEqualTo("refund policy");
        assertThat(reply.tenantId()).isEqualTo("acme");
        assertThat(reply.hits()).hasSize(1);
        assertThat(reply.hits().get(0).docId()).isEqualTo("doc-1");
        assertThat(reply.hits().get(0).source()).isEqualTo("vector");
    }
}
