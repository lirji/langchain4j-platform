package com.lrj.platform.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeRoleRequestFilterTest {

    @Test
    void queryRoleExposesOnlyReadSurface() {
        KnowledgeRoleRequestFilter filter =
                new KnowledgeRoleRequestFilter(KnowledgeRuntimeProperties.Role.QUERY);

        assertThat(filter.allows(request("POST", "/rag/query"))).isTrue();
        assertThat(filter.allows(request("POST", "/knowledge/query"))).isTrue();
        assertThat(filter.allows(request("POST", "/rag/image-search"))).isTrue();
        assertThat(filter.allows(request("GET", "/rag/documents"))).isTrue();
        assertThat(filter.allows(request("POST", "/rag/documents"))).isFalse();
        assertThat(filter.allows(request("POST", "/rag/image"))).isFalse();
        assertThat(filter.allows(request("GET", "/rag/ingestions/job-1"))).isFalse();
    }

    @Test
    void ingestApiRoleExposesOnlyDurableIngestionContract() {
        KnowledgeRoleRequestFilter filter =
                new KnowledgeRoleRequestFilter(KnowledgeRuntimeProperties.Role.INGEST_API);

        assertThat(filter.allows(request("POST", "/rag/ingestions"))).isTrue();
        assertThat(filter.allows(request("GET", "/rag/ingestions/job-1"))).isTrue();
        assertThat(filter.allows(request("GET", "/rag/query"))).isFalse();
        assertThat(filter.allows(request("POST", "/rag/documents"))).isFalse();
    }

    @Test
    void workerHasNoBusinessHttpSurfaceButKeepsProbes() {
        KnowledgeRoleRequestFilter filter =
                new KnowledgeRoleRequestFilter(KnowledgeRuntimeProperties.Role.INGEST_WORKER);

        assertThat(filter.allows(request("POST", "/rag/ingestions"))).isFalse();
        assertThat(filter.allows(request("GET", "/actuator/health/readiness"))).isTrue();
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }
}
