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
        candidate = candidate.change(candidate.draft(), Status.PUBLISHED, null, new Release(2, "reviewer", "editor", "job"), null, "editor", 2);
        server.expect(requestTo("http://query.test/rag/query")).andExpect(header("X-Internal-Token", "test-internal-token"))
                .andRespond(withSuccess("""
                        {"query":"多久退款","tenantId":"tenant","hits":[{"docId":"doc-1","text":"三天内退款","visibility":"tenant","version":"2"}]}
                        """, MediaType.APPLICATION_JSON));
        var probe = new KnowledgeRetrievalProbe(null, rest, "X-Internal-Token");
        assertThat(probe.evaluate(actor, "test-internal-token", candidate).getFirst().passed()).isTrue();
        server.verify();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"1", "3", "", "missing"})
    void missingOrDifferentVersionCannotProvePublication(String version) {
        var rest = new RestTemplate(); rest.setUriTemplateHandler(new DefaultUriBuilderFactory("http://query.test"));
        var server = MockRestServiceServer.bindTo(rest).build();
        String field = "missing".equals(version) ? "" : ",\"version\":\"" + version + "\"";
        server.expect(requestTo("http://query.test/rag/query")).andRespond(withSuccess(
                "{\"tenantId\":\"tenant\",\"hits\":[{\"docId\":\"doc-1\",\"visibility\":\"tenant\",\"text\":\"三天内退款\"" + field + "}]}", MediaType.APPLICATION_JSON));
        var draft = new Draft("退款", "三天内退款", null, List.of(new Check("多久退款", "三天")));
        var candidate = Candidate.create("tenant", "editor", null, null, "doc-1", draft, 1)
                .change(draft, Status.PUBLISHED, null, new Release(2, "reviewer", "editor", "job"), null, "editor", 2);
        assertThat(new KnowledgeRetrievalProbe(null, rest, "X-Internal-Token").evaluate(
                new TenantContext.Tenant("tenant", "editor", Set.of("ingest")), "test-token", candidate).getFirst().passed()).isFalse();
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
