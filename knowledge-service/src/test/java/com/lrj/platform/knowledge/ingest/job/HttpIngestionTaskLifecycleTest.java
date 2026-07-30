package com.lrj.platform.knowledge.ingest.job;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Set;

import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpIngestionTaskLifecycleTest {

    @Test
    void createsStableTaskEnvelopeAndSynchronizesKnowledgeState() {
        RestTemplate http = new RestTemplateBuilder().rootUri("http://async").build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        HttpIngestionTaskLifecycle lifecycle = new HttpIngestionTaskLifecycle(http);
        IngestionJob job = job();
        server.expect(once(), requestTo("http://async/async/tasks"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "taskId":"job-1",
                          "kind":"knowledge.ingestion",
                          "input":{
                            "knowledgeJobId":"job-1",
                            "documentId":"doc-1",
                            "documentVersion":1,
                            "sourceHash":"sha256:abc"
                          }
                        }
                        """))
                .andRespond(withNoContent());
        server.expect(once(), requestTo("http://async/async/tasks/job-1/status"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("""
                        {
                          "status":"RUNNING",
                          "result":{
                            "knowledgeJobId":"job-1",
                            "knowledgeStatus":"RECEIVED",
                            "documentId":"doc-1",
                            "documentVersion":1
                          }
                        }
                        """))
                .andRespond(withNoContent());

        lifecycle.ensureTask(job);
        lifecycle.synchronize(job);

        server.verify();
    }

    @Test
    void duplicateTaskConflictIsIdempotent() {
        RestTemplate http = new RestTemplateBuilder().rootUri("http://async").build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("http://async/async/tasks"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT));
        server.expect(requestTo("http://async/async/tasks/job-1"))
                .andRespond(withSuccess("""
                        {
                          "taskId":"job-1",
                          "tenantId":"acme",
                          "userId":"alice",
                          "kind":"knowledge.ingestion",
                          "status":"RUNNING",
                          "input":{}
                        }
                        """, MediaType.APPLICATION_JSON));

        new HttpIngestionTaskLifecycle(http).ensureTask(job());

        server.verify();
    }

    private static IngestionJob job() {
        return IngestionJob.received(
                "job-1", "key-1", "acme", "alice", Set.of("ingest"),
                "engineering", "trace-1", "doc-1", "guide.md", "manual",
                1, true,
                new DocumentSourceRef(
                        "knowledge", "acme/doc-1/v1/source", "sha256:abc",
                        "text/markdown", 5),
                Set.of(IngestionSink.VECTOR, IngestionSink.REGISTRY),
                Instant.parse("2026-07-30T00:00:00Z"));
    }
}
