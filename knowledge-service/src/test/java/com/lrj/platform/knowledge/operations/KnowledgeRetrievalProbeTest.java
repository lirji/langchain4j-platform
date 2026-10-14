package com.lrj.platform.knowledge.operations;

import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.util.DefaultUriBuilderFactory;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static com.lrj.platform.knowledge.operations.KnowledgeOperations.*;

class KnowledgeRetrievalProbeTest {
    @Test void forwardsOnlyTheProvidedInternalCredentialAndChecksRetrievedDocument() {
        var rest = new RestTemplate(); rest.setUriTemplateHandler(new DefaultUriBuilderFactory("http://query.test"));
        var server = MockRestServiceServer.bindTo(rest).build();
        var actor = new TenantContext.Tenant("tenant", "editor", Set.of("ingest"));
        var candidate = Candidate.create("tenant", "editor", null, null, "doc-1",
                new Draft("退款", "三天内退款", null, List.of(new Check("多久退款", "三天"))), 1);
        server.expect(requestTo("http://query.test/rag/query")).andExpect(header("X-Internal-Token", "test-internal-token"))
                .andRespond(withSuccess("""
                        {"query":"多久退款","tenantId":"tenant","hits":[{"docId":"doc-1","text":"三天内退款","visibility":"tenant"}]}
                        """, MediaType.APPLICATION_JSON));
        var probe = new KnowledgeRetrievalProbe(null, rest, "X-Internal-Token");
        assertThat(probe.evaluate(actor, "test-internal-token", candidate).getFirst().passed()).isTrue();
        server.verify();
    }
    @Test void wrongTenantReplyIsNotAcceptedAsProbeEvidence() {
        var rest = new RestTemplate(); rest.setUriTemplateHandler(new DefaultUriBuilderFactory("http://query.test"));
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("http://query.test/rag/query")).andRespond(withSuccess(
                "{\"query\":\"x\",\"tenantId\":\"other\",\"hits\":[]}", MediaType.APPLICATION_JSON));
        var candidate = Candidate.create("tenant", "editor", null, null, "doc-1",
                new Draft("退款", "三天内退款", null, List.of(new Check("多久退款", "三天"))), 1);
        assertThatThrownBy(() -> new KnowledgeRetrievalProbe(null, rest, "X-Internal-Token").evaluate(
                new TenantContext.Tenant("tenant", "editor", Set.of("ingest")), "test-token", candidate)).isInstanceOf(OperationsException.class);
    }
}
