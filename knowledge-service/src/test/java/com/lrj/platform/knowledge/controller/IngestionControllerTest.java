package com.lrj.platform.knowledge.controller;

import com.lrj.platform.knowledge.ingest.job.DocumentSourceRef;
import com.lrj.platform.knowledge.ingest.job.IngestionJob;
import com.lrj.platform.knowledge.ingest.job.IngestionSink;
import com.lrj.platform.knowledge.ingest.job.IngestionSubmissionService;
import com.lrj.platform.observability.TraceIdFilter;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IngestionControllerTest {

    private final IngestionSubmissionService submissions = mock(IngestionSubmissionService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new IngestionController(submissions))
            .setControllerAdvice(new KnowledgeExceptionHandler())
            .build();

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        MDC.clear();
    }

    @Test
    void acceptedResponseAndCommandPreserveAuthenticatedContext() throws Exception {
        TenantContext.set(new TenantContext.Tenant(
                "acme", "alice", Set.of("ingest", "chat"), "acme_engineering"));
        MDC.put(TraceIdFilter.MDC_KEY, "trace-1");
        when(submissions.submit(any())).thenReturn(job());

        mvc.perform(multipart("/rag/ingestions")
                        .file(new MockMultipartFile(
                                "file", "guide.txt", "text/plain", "hello".getBytes()))
                        .header("Idempotency-Key", "acme:doc-1:1")
                        .param("documentId", "doc-1")
                        .param("documentVersion", "1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-1"))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.traceId").value("trace-1"));

        ArgumentCaptor<IngestionSubmissionService.SubmitCommand> command =
                ArgumentCaptor.forClass(IngestionSubmissionService.SubmitCommand.class);
        verify(submissions).submit(command.capture());
        assertThat(command.getValue().tenantId()).isEqualTo("acme");
        assertThat(command.getValue().userId()).isEqualTo("alice");
        assertThat(command.getValue().scopes()).contains("ingest", "chat");
        assertThat(command.getValue().department()).isEqualTo("acme_engineering");
        assertThat(command.getValue().traceId()).isEqualTo("trace-1");
        assertThat(command.getValue().displayName()).isEqualTo("guide.txt");
    }

    @Test
    void missingIngestScopeFailsClosedBeforeSourceWrite() throws Exception {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));

        mvc.perform(multipart("/rag/ingestions")
                        .file(new MockMultipartFile(
                                "file", "guide.txt", "text/plain", "hello".getBytes()))
                        .header("Idempotency-Key", "acme:doc-1:1")
                        .param("documentId", "doc-1")
                        .param("documentVersion", "1"))
                .andExpect(status().isForbidden());

        verify(submissions, never()).submit(any());
    }

    private IngestionJob job() {
        Instant now = Instant.parse("2026-07-30T00:00:00Z");
        return IngestionJob.received(
                "job-1",
                "acme:doc-1:1",
                "acme",
                "alice",
                Set.of("ingest", "chat"),
                "acme_engineering",
                "trace-1",
                "doc-1",
                "guide.txt",
                "manual",
                1,
                true,
                new DocumentSourceRef(
                        "knowledge-sources",
                        "documents/acme/doc-1/v1/sha256-"
                                + "a".repeat(64) + "/source",
                        "sha256:" + "a".repeat(64),
                        "text/plain",
                        5),
                Set.of(IngestionSink.VECTOR, IngestionSink.REGISTRY),
                now);
    }
}
