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

    @Test
    void combinedCanDisableAllSynchronousUploadEntrypointsWithoutHidingReadsOrShare() {
        var filter = new KnowledgeRoleRequestFilter(KnowledgeRuntimeProperties.Role.COMBINED, false);
        for (String path : java.util.List.of("/rag/documents", "/rag/documents/", "/rag/%64ocuments", "/rag/documents;mode=sync", "/rag//documents", "/rag/image", "/rag/obsidian/import")) {
            assertThat(filter.allows(request("POST", path))).isFalse();
        }
        assertThat(filter.allows(request("GET", "/rag/documents"))).isTrue();
        assertThat(filter.allows(request("POST", "/rag/ingestions"))).isTrue();
        assertThat(filter.allows(request("DELETE", "/rag/documents/doc-1"))).isTrue();
        assertThat(filter.allows(request("POST", "/rag/documents/doc-1/share"))).isTrue();
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }
}
